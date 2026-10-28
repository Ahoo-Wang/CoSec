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

import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * [SnapshotSource.recompile] (RFC 0007 §3.1 N3, review finding S4): once a family compiler's inputs change, every app
 * and tenant document is compiled with the new ones, including one whose load was in flight, and none keeps a
 * compilation with the old ones when it cannot be compiled again.
 */
class SnapshotRecompileTest {
    private val clock = ManualClock()
    private val ticker = AtomicLong()
    private val metrics = RecordingMetrics()

    /** Compiles to the revision and the [inputs] it compiled with; [during] runs inside a compilation of its key. */
    private class InputsCompiler : SnapshotCompiler<Compiled> {
        @Volatile
        var inputs = 1L

        val compiled: MutableList<SnapshotKey> = CopyOnWriteArrayList()

        @Volatile
        var during: Pair<SnapshotKey, () -> Unit>? = null

        override fun compile(key: SnapshotKey, document: SnapshotDocument): Compiled {
            compiled += key
            val used = inputs
            during?.takeIf { it.first == key }?.let {
                during = null
                it.second()
            }
            return Compiled(key, document.revision, listOf("inputs-$used"), emptySet())
        }
    }

    private fun source(
        store: SnapshotDocumentStore,
        compiler: InputsCompiler,
        options: SnapshotOptions = SnapshotOptions(revalidateInterval = Duration.ZERO),
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

    private fun ControllableStore.publish(key: SnapshotKey, revision: Long) = put(key, document(revision))

    private fun SnapshotSource<Compiled, Compiled>.installed(key: SnapshotKey): DocumentState<Compiled> =
        peekAll(listOf(key))!!.scoped.single().state

    private fun DocumentState<Compiled>.inputs(): String = (this as DocumentState.Present).compiled.entries.single()

    @Test
    fun `a load that compiled with the old inputs while they changed is compiled again before anyone is served it`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        val compiler = InputsCompiler()
        source(store, compiler).use { source ->
            source.load().join()
            // The tenant's cold load has read inputs 1 when they change and the source is told to recompile.
            compiler.during = TENANT_A to {
                compiler.inputs = 2
                source.recompile()
            }
            val waited = source.loadAll(listOf(TENANT_A)).join()
            waited.scoped.single().state.inputs().assert().isEqualTo("inputs-2")
            source.installed(TENANT_A).inputs().assert().isEqualTo("inputs-2")
            compiler.compiled.count { it == TENANT_A }.assert().isEqualTo(2)
            metrics.installs.filter { it.key == TENANT_A }.map { (it.state as DocumentState.Present<*>).compiled }
                .assert().containsExactly(Compiled(TENANT_A, 1, listOf("inputs-2"), emptySet()))
        }
    }

    @Test
    fun `installed documents compile again, the platform and documents never compiled do not`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        store.publish(APP_A, 1)
        val compiler = InputsCompiler()
        source(store, compiler).use { source ->
            source.loadAll(listOf(TENANT_A, APP_A, TENANT_B)).join()
            val reads = store.conditionalReads.get() + store.reads.get()
            compiler.inputs = 2
            source.recompile().join()
            source.installed(TENANT_A).inputs().assert().isEqualTo("inputs-2")
            source.installed(APP_A).inputs().assert().isEqualTo("inputs-2")
            source.installed(TENANT_B).assert().isEqualTo(DocumentState.Absent)
            compiler.compiled.count { it == SnapshotKey.Platform }.assert().isEqualTo(1)
            (store.conditionalReads.get() + store.reads.get() - reads).assert().isEqualTo(2)
        }
    }

    @Test
    fun `a document that cannot be compiled again fails closed until a retry compiles it`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        store.publish(TENANT_B, 1)
        val compiler = InputsCompiler()
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, rejectedReloadRetry = Duration.ofMillis(10))
        source(store, compiler, options).use { source ->
            source.loadAll(listOf(TENANT_A)).join()
            source.loadAll(listOf(TENANT_B)).join()
            store.failure = IllegalStateException("redis down")
            // Without a change of inputs a failed reload keeps what is installed (F6).
            source.reload(TENANT_B).join()
            source.installed(TENANT_B).inputs().assert().isEqualTo("inputs-1")
            compiler.inputs = 2
            source.recompile().join()
            val failed = source.installed(TENANT_A) as DocumentState.Failed
            failed.error.assert().isInstanceOf(SnapshotUnavailableException::class.java)
            store.failure = null
            awaitCondition { source.installed(TENANT_A) is DocumentState.Present }
            source.installed(TENANT_A).inputs().assert().isEqualTo("inputs-2")
        }
    }

    @Test
    fun `a document failed by a recompile keeps retrying while the store stays down`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        val compiler = InputsCompiler()
        // No revalidation: only the retries can bring the document back.
        val options = SnapshotOptions(
            revalidateInterval = Duration.ZERO,
            rejectedReloadRetry = Duration.ofMillis(1),
            prewarmInitialBackoff = Duration.ofMillis(1),
            prewarmMaxBackoff = Duration.ofMillis(5),
        )
        source(store, compiler, options).use { source ->
            source.loadAll(listOf(TENANT_A)).join()
            store.failure = IllegalStateException("redis down")
            compiler.inputs = 2
            source.recompile().join()
            source.installed(TENANT_A).assert().isInstanceOf(DocumentState.Failed::class.java)
            // The first retry fails too, and the ones after it.
            val reads = store.conditionalReads.get()
            awaitCondition { store.conditionalReads.get() >= reads + 3 }
            store.failure = null
            awaitCondition { source.installed(TENANT_A) is DocumentState.Present }
            source.installed(TENANT_A).inputs().assert().isEqualTo("inputs-2")
        }
    }

    @Test
    fun `a load started after a recompile reads in full rather than keep the old compilation`() {
        val store = ControllableStore()
        store.publish(SnapshotKey.Platform, 1)
        store.publish(TENANT_A, 1)
        store.publish(TENANT_B, 1)
        val compiler = InputsCompiler()
        // One reload at a time: tenant B waits behind tenant A's held read.
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, reloadConcurrency = 1)
        source(store, compiler, options).use { source ->
            source.loadAll(listOf(TENANT_A, TENANT_B)).join()
            store.hold = true
            compiler.inputs = 2
            val recompiling = source.recompile()
            // Revalidation reaches tenant B first; a conditional read would answer Unchanged and keep inputs 1.
            source.revalidate()
            store.pending.single { it.key == TENANT_B }.also { store.pending.remove(it) }.release()
            source.installed(TENANT_B).inputs().assert().isEqualTo("inputs-2")
            store.hold = false
            store.releaseAll()
            recompiling.join()
            source.installed(TENANT_A).inputs().assert().isEqualTo("inputs-2")
            source.installed(TENANT_B).inputs().assert().isEqualTo("inputs-2")
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
