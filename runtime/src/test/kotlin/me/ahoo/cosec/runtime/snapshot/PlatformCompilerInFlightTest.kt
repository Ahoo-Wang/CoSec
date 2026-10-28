/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.snapshot.DocumentSnapshots
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotCompiler
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.RepeatedTest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * N3 with a load in flight (review finding S4): a tenant document whose compilation read the previous platform's
 * attributes and installs after the platform changed is compiled again, so it never stays compiled against them.
 */
class PlatformCompilerInFlightTest {
    private val store = InMemorySnapshotDocumentStore()
    private val revisions = HashMap<SnapshotKey, Long>()

    private fun publish(key: SnapshotKey, body: String) {
        val revision = (revisions[key] ?: 0) + 1
        revisions[key] = revision
        val text = """{"schema":2,"revision":$revision,"publishedAt":"2026-10-27T00:00:00Z"$body}"""
        store.put(key, SnapshotDocument(2, revision, Instant.parse("2026-10-27T00:00:00Z"), text.encodeToByteArray()))
    }

    @RepeatedTest(50)
    fun `a document compiled against the previous platform while it changed is compiled again`() {
        val acme = SnapshotKey.Tenant("acme")
        publish(SnapshotKey.Platform, DECLARES_IN_FENCE)
        publish(acme, READS_IN_FENCE)
        val compiler = PlatformCompiler()
        val firstCompiled = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val compiledAgain = CountDownLatch(1)
        val tenantCompiles = AtomicInteger()
        // Holds the tenant's first compilation after it has read the platform's inputs and before it installs.
        val gated = SnapshotCompiler<CompiledDocument> { key, document ->
            if (key != acme) {
                return@SnapshotCompiler compiler.compile(key, document)
            }
            val attempt = tenantCompiles.incrementAndGet()
            try {
                compiler.compile(key, document)
            } finally {
                if (attempt == 1) {
                    firstCompiled.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                } else {
                    compiledAgain.countDown()
                }
            }
        }
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, compileThreads = 2)
        DocumentSnapshots.source(store, gated, options).use { source ->
            compiler.bind(source)
            val views = DocumentSnapshotViews(source, platformRequired = true)
            source.load().join()
            val key = ViewKey.of(scope("acme"), blocked = false)

            // 1. The tenant's cold load compiles against the platform that declares ext.geo.inFence, and is held.
            val loading = views.load(key)
            firstCompiled.await(10, TimeUnit.SECONDS).assert().isTrue()
            // 2. The platform stops declaring it while that load is in flight: the tenant slot holds no entry yet.
            publish(SnapshotKey.Platform, "")
            source.reload(SnapshotKey.Platform).join()
            // 3. The held compilation installs what it compiled against the previous platform.
            releaseFirst.countDown()
            loading.join().tenant.assert().isNotNull()

            // It is compiled again, against the platform that no longer declares the attribute, and fails closed.
            compiledAgain.await(10, TimeUnit.SECONDS).assert().describedAs("never compiled again").isTrue()
            awaitUnresolved(views, key)
        }
    }

    private fun awaitUnresolved(views: DocumentSnapshotViews, key: ViewKey) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (true) {
            val view = views.load(key).join()
            if (view.tenant == null) {
                (view.unresolved.single() as Cause.SourceUnavailable).document.assert().isEqualTo(DocumentKind.TENANT)
                return
            }
            check(System.nanoTime() < deadline) { "The recompiled tenant document was not installed." }
            Thread.onSpinWait()
        }
    }

    private fun scope(tenant: String): Scope {
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(TenantId(tenant), true, memberships)
        return (
            ScopeResolver.resolve(
                subject,
                DeclaredScope("order", ""),
                HostScope(null)
            ) as ScopeResolution.Resolved
            ).scope
    }

    private companion object {
        const val DECLARES_IN_FENCE = ""","attributes":[{"path":"ext.geo.inFence","type":"bool"}]"""
        const val READS_IN_FENCE =
            ""","policies":[{"id":"fenced","statements":[{"effect":"allow","action":"order:read",""" +
                """"condition":{"eq":["ext.geo.inFence",true]}}]}],""" +
                """"bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"fenced"}]"""
    }
}
