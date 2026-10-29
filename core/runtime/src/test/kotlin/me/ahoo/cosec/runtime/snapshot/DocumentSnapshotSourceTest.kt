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
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotStaleException
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The 8.0 source (RFC 0004 §7.4): the shared platform document and the app and tenant families. RFC 0003's guarantees
 * (single flight, install order, revalidation, staleness, failing closed) hold per key in every family; the other test
 * classes cover them on RFC 0003's source, and `SnapshotInstallOrderPropertyTest` on both.
 */
class DocumentSnapshotSourceTest {
    private val clock = ManualClock()
    private val ticker = AtomicLong()

    private fun source(
        store: SnapshotDocumentStore,
        compiler: SnapshotCompiler<Compiled> = FakeCompiler(),
        options: SnapshotOptions = SnapshotOptions(revalidateInterval = Duration.ZERO),
        metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
    ) = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        DocumentSnapshots.families(compiler, options),
        options,
        clock,
        metrics,
        directExecutor,
        null,
    ) { ticker.incrementAndGet() }

    private fun ControllableStore.publish(key: SnapshotKey, revision: Long) = put(
        key,
        document(revision, "$key-$revision")
    )

    @Test
    fun `a request reads the platform and the documents it needs, each at one revision`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 3)
        store.publish(TENANT_A, 5)
        store.publish(APP_A, 7)
        source(store).use { source ->
            source.peekAll(listOf(TENANT_A, APP_A)).assert().isNull()
            val set = source.loadAll(listOf(TENANT_A, APP_A)).join()
            set.shared.revision.assert().isEqualTo(3)
            set.scoped.map { it.key to it.revision }.assert().containsExactly(TENANT_A to 5L, APP_A to 7L)
            set.entry(APP_A)!!.revision.assert().isEqualTo(7)
            set.entry(TENANT_B).assert().isNull()
            val peeked = source.peekAll(listOf(TENANT_A, APP_A))!!
            peeked.scoped.map { it.revision }.assert().containsExactly(5L, 7L)
            // A blocked request needs only the platform.
            source.peekAll(emptyList())!!.shared.revision.assert().isEqualTo(3)
            source.peekAll(listOf(TENANT_B)).assert().isNull()
        }
    }

    @Test
    fun `a document that does not exist is absent, one that disappears fails closed`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        source(store).use { source ->
            source.loadAll(listOf(TENANT_B)).join().scoped.single().state.assert().isEqualTo(DocumentState.Absent)
            source.loadAll(listOf(TENANT_A)).join()
            store.remove(TENANT_A)
            source.revalidate().join()
            (source.peekAll(listOf(TENANT_A))!!.scoped.single().state as DocumentState.Failed).error.assert()
                .isInstanceOf(SnapshotUnavailableException::class.java)
        }
    }

    @Test
    fun `concurrent requests for one tenant share one read`() {
        val store = ControllableStore().apply { hold = true }
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        source(store).use { source ->
            val first = source.loadAll(listOf(TENANT_A))
            val second = source.loadAll(listOf(TENANT_A))
            store.pending.count { it.key == TENANT_A }.assert().isEqualTo(1)
            store.releaseAll()
            first.join().scoped.single().revision.assert().isEqualTo(1)
            second.join().scoped.single().revision.assert().isEqualTo(1)
        }
    }

    @Test
    fun `each family evicts within its own bound`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        listOf(TENANT_A, TENANT_B, APP_A, APP_B).forEach { store.publish(it, 1) }
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 2, maxTenants = 1)
        source(store, options = options).use { source ->
            source.loadAll(listOf(TENANT_A, APP_A)).join()
            source.loadAll(listOf(TENANT_B, APP_B)).join()
            awaitCondition { !source.holds(TENANT_A) }
            source.holds(TENANT_B).assert().isTrue()
            source.holds(APP_A).assert().isTrue()
            source.holds(APP_B).assert().isTrue()
            source.evict(APP_A).assert().isTrue()
            source.evict(TENANT_A).assert().isFalse()
        }
    }

    @Test
    fun `a change to a tenant reloads that tenant only`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        store.publish(TENANT_B, 1)
        source(store).use { source ->
            source.loadAll(listOf(TENANT_A, TENANT_B)).join()
            store.readLog.clear()
            store.publish(TENANT_A, 2)
            source.peekAll(listOf(TENANT_A))!!.scoped.single().revision.assert().isEqualTo(2)
            store.readLog.assert().containsExactly(TENANT_A)
            source.reload(TENANT_B).join().revision.assert().isEqualTo(1)
            store.publish(SnapshotKey.Platform, 2)
            source.peekAll(emptyList())!!.shared.revision.assert().isEqualTo(2)
            // Another source's key is not this one's to reload.
            store.notifyChanged(SnapshotKey.Platform)
        }
    }

    @Test
    fun `a stale tenant document fails a fail-closed tenant`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        val options = SnapshotOptions(
            revalidateInterval = Duration.ZERO,
            failClosedTenants = setOf("tenant-a"),
            failClosedMaxLag = Duration.ofMinutes(1),
        )
        source(store, options = options).use { source ->
            val set = source.loadAll(listOf(TENANT_A)).join()
            source.checkFresh(set, "tenant-a")
            clock.advance(Duration.ofMinutes(2))
            assertThrownBy<SnapshotStaleException> { source.checkFresh(set, "tenant-a") }
            source.checkFresh(set, "tenant-b")
            set.lagAt(clock.instant()).assert().isEqualTo(Duration.ofMinutes(2))
        }
    }

    @Test
    fun `keys outside the source are refused`() {
        val store = ControllableStore()
        source(store).use { source ->
            assertThrownBy<IllegalArgumentException> { source.loadAll(listOf(SnapshotKey.Platform)) }
            assertThrownBy<IllegalArgumentException> { source.peekAll(listOf(SnapshotKey.Platform)) }
            // RFC 0003's app-only request shape works too: the platform and one app.
            source.load("app-a").join().policiesEntry.key.assert().isEqualTo(SnapshotKey.Platform)
        }
        testSource(store).use { legacy ->
            assertThrownBy<IllegalArgumentException> { legacy.loadAll(listOf(TENANT_A)) }
            assertThrownBy<IllegalArgumentException> { legacy.reload(TENANT_A) }
            assertThrownBy<IllegalArgumentException> { legacy.prewarm(listOf(TENANT_A)).join() }
        }
        assertThrownBy<IllegalArgumentException> { SnapshotFamily(SnapshotKind.PLATFORM, FakeCompiler(), 1) }
        assertThrownBy<IllegalArgumentException> { SnapshotFamily(SnapshotKind.TENANT, FakeCompiler(), 0) }
        assertThrownBy<IllegalArgumentException> { SnapshotFamily(SnapshotKind.TENANT, FakeCompiler(), 1).idOf(APP_A) }
        assertThrownBy<IllegalArgumentException> { SnapshotOptions(maxTenants = 0) }
    }

    @Test
    fun `the document source compiles real documents`() {
        val store = InMemorySnapshotDocumentStore()
        fun put(key: SnapshotKey, body: String) = store.put(
            key,
            SnapshotDocument(
                2,
                1,
                Instant.EPOCH,
                """{"schema":2,"revision":1,"publishedAt":"1970-01-01T00:00:00Z"$body}""".encodeToByteArray()
            ),
        )
        put(SnapshotKey.Platform, ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*"}]}]""")
        put(TENANT_A, ""","bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"p"}]""")
        put(TENANT_B, ""","policies":[{"id":"perm:x/y","statements":[]}]""")
        DocumentSnapshots.source(
            store,
            PolicyDocumentCompiler(),
            SnapshotOptions(revalidateInterval = Duration.ZERO)
        ).use { source ->
            val set = source.loadAll(listOf(TENANT_A, TENANT_B)).join()
            (set.shared.state as DocumentState.Present<CompiledDocument>).compiled.kind.assert().isEqualTo(
                DocumentKind.PLATFORM
            )
            (set.scoped[0].state as DocumentState.Present<CompiledDocument>).compiled.owner.assert().isEqualTo(
                "tenant-a"
            )
            // A document that does not compile fails closed, alone.
            set.scoped[1].state.assert().isInstanceOf(DocumentState.Failed::class.java)
            source.prewarm(listOf(SnapshotKey.Platform, TENANT_A)).join()
            source.isPrewarmed.assert().isTrue()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met in time." }
            Thread.onSpinWait()
        }
    }
}
