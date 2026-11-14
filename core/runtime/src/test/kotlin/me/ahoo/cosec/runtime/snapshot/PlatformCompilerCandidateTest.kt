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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.snapshot

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.document.DocumentCompileException
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * `SNP-10`: a staged platform candidate is compiled for the shadow comparison and for nothing else. What the instance
 * enforces goes on compiling with the limits and attributes of the platform document it installed.
 */
class PlatformCompilerCandidateTest {
    private val store = InMemorySnapshotDocumentStore()
    private val acme = SnapshotKey.Tenant("acme")

    private fun document(revision: Long, body: String): SnapshotDocument {
        val text = """{"schema":2,"revision":$revision,"publishedAt":"2026-10-27T00:00:00Z"$body}"""
        return SnapshotDocument(2, revision, Instant.parse("2026-10-27T00:00:00Z"), text.encodeToByteArray())
    }

    private fun scope(tenant: String): Scope {
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(TenantId(tenant), true, memberships)
        return (ScopeResolver.resolve(subject, "", HostScope(null)) as ScopeResolution.Resolved).scope
    }

    private fun source(compiler: PlatformCompiler) =
        DocumentSnapshots.source(store, compiler, SnapshotOptions(revalidateInterval = QUIET_PASS)).also(compiler::bind)

    @Test
    fun `a staged platform candidate leaves the enforced documents as they were`() {
        store.put(SnapshotKey.Platform, document(1, DECLARES))
        store.put(acme, document(1, READS))
        val compiler = PlatformCompiler()
        source(compiler).use { source ->
            val views = DocumentSnapshotViews(source, platformRequired = true)
            source.load().join()
            val key = ViewKey.of(scope("acme"), blocked = false)
            views.load(key).join().unresolved.assert().isEmpty()
            val live = compiler.current

            // Staged, not published: the store of record still holds revision 1, which declares the attribute.
            val staged = StoredCandidate("w", 1, document(2, ""))
            val candidates = CachingCandidateSource(
                { candidate -> staged.takeIf { candidate == SnapshotKey.Platform } },
                compiler::compileCandidate,
            )
            val compiled = runBlocking { candidates.candidate(SnapshotKey.Platform) }
            compiled.assert().isInstanceOf(Candidate.Ready::class.java)

            compiler.current.assert().isSameAs(live)
            source.reload(acme).join()
            views.load(key).join().let {
                it.tenant.assert().isNotNull()
                it.unresolved.assert().isEmpty()
            }
        }
    }

    @Test
    fun `publishing the same change does reach them`() {
        store.put(SnapshotKey.Platform, document(1, DECLARES))
        store.put(acme, document(1, READS))
        val compiler = PlatformCompiler()
        source(compiler).use { source ->
            val views = DocumentSnapshotViews(source, platformRequired = true)
            source.load().join()
            val key = ViewKey.of(scope("acme"), blocked = false)
            views.load(key).join().unresolved.assert().isEmpty()
            val live = compiler.current

            store.put(SnapshotKey.Platform, document(2, ""))
            source.reload(SnapshotKey.Platform).join()
            compiler.current.assert().isNotSameAs(live)
            source.reload(acme).join()
            views.load(key).join().tenant.assert().isNull()
        }
    }

    @Test
    fun `a platform candidate compiles against its own limits and attributes`() {
        val compiler = PlatformCompiler()
        compiler.compileCandidate(SnapshotKey.Platform, document(1, DECLARES + "," + READS.removePrefix(",")))
        assertThrownBy<DocumentCompileException> {
            compiler.compileCandidate(SnapshotKey.Platform, document(1, READS))
        }
    }

    @Test
    fun `a tenant candidate compiles against the enforced platform`() {
        store.put(SnapshotKey.Platform, document(1, DECLARES))
        val compiler = PlatformCompiler()
        source(compiler).use { source ->
            source.load().join()
            compiler.compileCandidate(acme, document(1, READS))
        }
    }

    private companion object {
        val QUIET_PASS: Duration = Duration.ofSeconds(50)
        const val DECLARES = ""","attributes":[{"path":"ext.geo.inFence","type":"bool"}]"""
        const val READS =
            ""","policies":[{"id":"fence","statements":[{"effect":"deny","action":"order:read",""" +
                """"condition":{"eq":["ext.geo.inFence",true]}}]}]"""
    }
}
