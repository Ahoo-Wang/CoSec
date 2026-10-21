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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * RFC 0003 §8.4 P6: for any interleaving of publishes, lost notifications, read completions, compilations,
 * revalidations and resets, installs of a key follow the order its loads started (so revisions never go backwards
 * while the store's only grow), at most one load per key is in flight, and once everything settles the installed
 * revision is the store's.
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

    private val keys = listOf(SnapshotKey.Policies, APP_A, APP_B)

    @Test
    fun installsFollowLoadOrderAndConverge() {
        repeat(300) { seed -> runSeed(seed) }
    }

    /** One run: the store, the source and what the test has published. */
    private class Run(seed: Int) {
        val random = Random(seed)
        val store = ControllableStore().apply { hold = true }
        val executor = ManualExecutor()
        val clock = ManualClock()
        val metrics = RecordingMetrics()
        val revisions = mutableMapOf<SnapshotKey, Long>()
        val ticks = AtomicLong()
        val source = SnapshotSource(
            store,
            FakeCompiler(),
            FakeCompiler(),
            // One app at most, so loading the other evicts it: eviction runs concurrently on the scheduler.
            SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 1),
            clock,
            metrics,
            executor,
            null,
            ticks::incrementAndGet,
        )

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
            when (random.nextInt(10)) {
                0, 1 -> publish(keys.random(random))
                2 -> releaseOne()
                3 -> executor.runOne(random)
                4 -> source.revalidate()
                5 -> if (random.nextInt(4) == 0) store.reset()
                6 -> source.load("app-a")
                7 -> source.load("app-b")
                8 -> store.failure = if (random.nextInt(3) == 0) IllegalStateException("redis down") else null
                else -> clock.advance(Duration.ofSeconds(1))
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
                ).isEqualTo(run.revisions[SnapshotKey.Policies] ?: 0)
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
