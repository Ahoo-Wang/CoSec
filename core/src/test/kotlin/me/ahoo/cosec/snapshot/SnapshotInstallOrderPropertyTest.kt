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

package me.ahoo.cosec.snapshot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * RFC 0003 §8.4 P6: for any interleaving of publishes, lost notifications, read completions, compilations,
 * revalidations and resets, installs of a key follow the order its loads started (so revisions never go backwards
 * while the store's only grow), at most one load per key is in flight, and once everything settles the installed
 * revision is the store's.
 *
 * Both sources run it: RFC 0003's (policies and one family of apps) and the 8.0 one (the platform, and families of
 * apps and tenants, each bounded to one document so that loads of one family evict while the other installs).
 *
 * On the 8.0 source the family compiler's inputs also change (RFC 0007 §3.1 N3), between steps and in the middle of a
 * compilation that has already read them, as a platform change does while other documents load (review finding S4).
 * Once everything settles, every installed document was compiled with the current inputs.
 */
class SnapshotInstallOrderPropertyTest {
    /** Queues compilations so the test decides when each one runs. */
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runOne(random: Random) {
            if (tasks.isNotEmpty()) {
                tasks.removeAt(random.nextInt(tasks.size)).run()
            }
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }

    /**
     * The family compiler of the 8.0 run: remembers the inputs each result compiled with, and may change them (and
     * [SnapshotSource.recompile]) after reading them, before the compilation installs.
     */
    private class InputsCompiler(private val random: Random) : SnapshotCompiler<Compiled> {
        private val delegate = FakeCompiler()
        private val usedBy = java.util.IdentityHashMap<Compiled, Long>()

        var inputs = 0L
        var changing = true
        lateinit var source: SnapshotSource<Compiled, Compiled>

        fun change() {
            inputs++
            source.recompile()
        }

        fun inputsOf(compiled: Compiled): Long? = synchronized(usedBy) { usedBy[compiled] }

        override fun compile(key: SnapshotKey, document: SnapshotDocument): Compiled {
            val used = inputs
            val compiled = delegate.compile(key, document)
            synchronized(usedBy) { usedBy[compiled] = used }
            if (changing && random.nextInt(8) == 0) {
                change()
            }
            return compiled
        }
    }

    private val keys = listOf(SnapshotKey.Platform, APP_A, APP_B)
    private val documentKeys = listOf(SnapshotKey.Platform, APP_A, APP_B, TENANT_A, TENANT_B)

    @Test
    fun installsFollowLoadOrderAndConverge() {
        // propertyStress raises the number of seeds (RFC 0008 §3.5).
        repeat(maxOf(300, Integer.getInteger("cosec.property.iterations", 0))) { seed -> runSeed(seed) }
    }

    @Test
    fun installsFollowLoadOrderAndConvergeInEveryFamily() {
        repeat(maxOf(300, Integer.getInteger("cosec.property.iterations", 0))) { seed -> runDocumentSeed(seed) }
    }

    /** One run: the store, the source and what the test has published. */
    private class Run(seed: Int, val documents: Boolean = false) {
        val random = Random(seed)
        val store = ControllableStore().apply { hold = true }
        val executor = ManualExecutor()
        val clock = ManualClock()
        val metrics = RecordingMetrics()
        val revisions = mutableMapOf<SnapshotKey, Long>()
        val ticks = AtomicLong()

        // One app and one tenant at most, so loading the other evicts it: eviction runs concurrently on the scheduler.
        // Retries after a failed recompile would run on the scheduler too; settling revalidates instead.
        val options = SnapshotOptions(
            revalidateInterval = Duration.ZERO,
            maxApps = 1,
            maxTenants = 1,
            rejectedReloadRetry = Duration.ofHours(1),
        )
        val inputs = InputsCompiler(random)
        val source = if (documents) {
            SnapshotSource(
                store,
                SnapshotKey.Platform,
                FakeCompiler(),
                DocumentSnapshots.families(inputs, options),
                options,
                clock,
                metrics,
                executor,
                null,
                ticks::incrementAndGet,
            )
        } else {
            appSource(store, FakeCompiler(), FakeCompiler(), options, clock, metrics, executor, null, ticks::incrementAndGet)
        }

        init {
            inputs.source = source
            inputs.changing = documents
        }

        fun publish(key: SnapshotKey) {
            val revision = (revisions[key] ?: 0) + 1
            revisions[key] = revision
            store.dropNotifications = random.nextInt(4) == 0
            store.put(key, document(revision, "$key-$revision"))
            store.dropNotifications = false
        }

        fun releaseOne() {
            if (store.pending.isNotEmpty()) {
                store.pending.removeAt(random.nextInt(store.pending.size)).release()
            }
        }

        fun step(keys: List<SnapshotKey>) {
            if (documents && random.nextInt(12) == 0) {
                inputs.change()
            }
            when (random.nextInt(10)) {
                0, 1 -> publish(keys.random(random))
                2 -> releaseOne()
                3 -> executor.runOne(random)
                4 -> source.revalidate()
                5 -> if (random.nextInt(4) == 0) store.reset()
                6, 7 -> load(keys.filter { it is SnapshotKey.App || it is SnapshotKey.Tenant }.random(random))
                8 -> store.failure = if (random.nextInt(3) == 0) IllegalStateException("redis down") else null
                else -> clock.advance(Duration.ofSeconds(1))
            }
        }

        /** A request that needs [key] besides the shared document. */
        fun load(key: SnapshotKey): CompletableFuture<*> =
            if (documents) source.loadAll(listOf(key)) else source.load((key as SnapshotKey.App).appId)

        /** Loads [key] until it answers, releasing reads and compilations. */
        fun loadSettled(key: SnapshotKey): SnapshotSet<Compiled, Compiled> {
            store.hold = false
            val loading = source.loadAll(listOf(key))
            while (!loading.isDone) {
                executor.runAll()
                store.releaseAll()
            }
            store.hold = true
            return loading.join()
        }

        fun settleAll(key: SnapshotKey) {
            store.failure = null
            load(key)
            repeat(3) {
                while (store.pending.isNotEmpty() || executor.tasks.isNotEmpty()) {
                    store.releaseAll()
                    executor.runAll()
                }
                source.revalidate()
            }
        }

        fun settle(appId: String) {
            store.failure = null
            source.load(appId)
            repeat(3) {
                while (store.pending.isNotEmpty() || executor.tasks.isNotEmpty()) {
                    store.releaseAll()
                    executor.runAll()
                }
                source.revalidate()
            }
        }
    }

    private fun runDocumentSeed(seed: Int) {
        val run = Run(seed, documents = true)
        run.source.use { _ ->
            repeat(200) {
                run.step(documentKeys)
                documentKeys.forEach { key ->
                    run.store.pending.count { it.key == key }.assert().isLessThanOrEqualTo(
                        1
                    )
                }
            }
            run.inputs.changing = false
            for (key in documentKeys.drop(1)) {
                run.settleAll(key)
                val set = run.loadSettled(key)
                set.shared.revision.assert().describedAs(
                    "seed $seed"
                ).isEqualTo(run.revisions[SnapshotKey.Platform] ?: 0)
                val entry = set.scoped.single()
                entry.revision.assert().describedAs("seed $seed, $key").isEqualTo(run.revisions[key] ?: 0)
                val state = entry.state
                if (state is DocumentState.Present) {
                    run.inputs.inputsOf(state.compiled).assert().describedAs(
                        "seed $seed, $key compiled with old inputs"
                    ).isEqualTo(run.inputs.inputs)
                }
            }
            documentKeys.forEach { key ->
                val installed = run.metrics.installs.filter { it.key == key }.map { it.revision }
                installed.assert().describedAs("seed $seed, key $key: $installed").isSorted()
            }
            run.metrics.regressions.assert().isEmpty()
        }
    }

    private fun runSeed(seed: Int) {
        val run = Run(seed)
        run.source.use { source ->
            repeat(200) {
                run.step(keys)
                keys.forEach { key -> run.store.pending.count { it.key == key }.assert().isLessThanOrEqualTo(1) }
            }
            listOf(APP_A, APP_B).forEach { app ->
                run.settle(app.appId)
                // Eviction runs concurrently, so read through load(): a hit, or a cold load of a seen key from the store.
                run.store.hold = false
                val loading = source.load(app.appId)
                while (!loading.isDone) {
                    run.executor.runAll()
                    run.store.releaseAll()
                }
                run.store.hold = true
                val snapshot = loading.join()
                snapshot.policiesEntry.revision.assert().describedAs(
                    "seed $seed"
                ).isEqualTo(run.revisions[SnapshotKey.Platform] ?: 0)
                snapshot.appEntry!!.revision.assert().describedAs("seed $seed, $app").isEqualTo(run.revisions[app] ?: 0)
            }

            keys.forEach { key ->
                val installed = run.metrics.installs.filter { it.key == key }.map { it.revision }
                installed.assert().describedAs("seed $seed, key $key: $installed").isSorted()
            }
            run.metrics.regressions.assert().isEmpty()
        }
    }
}
