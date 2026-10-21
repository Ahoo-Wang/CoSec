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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Each request is answered exactly once, by the shared load or by its timeout, and the outcome is recorded before the
 * answer: whoever sees a cold load's result also sees its metric. Every answer that is not a loaded document fails
 * closed.
 */
class SnapshotLoadOutcomeTest {
    /** Keeps scheduled timeouts instead of running them, so the test decides when they fire. */
    private class ManualScheduler : ScheduledThreadPoolExecutor(1) {
        val timeouts: MutableList<Runnable> = CopyOnWriteArrayList()

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            timeouts.add(command)
            return super.schedule({}, 1, TimeUnit.DAYS)
        }

        fun fire() {
            timeouts.forEach { it.run() }
        }
    }

    private val store = ControllableStore().apply {
        put(SnapshotKey.Policies, document(1))
        hold = true
    }
    private val metrics = RecordingMetrics()
    private val scheduler = ManualScheduler()

    /** The metrics recorded at the moment the request's answer completes, and the answer. */
    private fun SnapshotSource<Compiled, Compiled>.loadObserved(): Pair<CompletableFuture<List<Pair<SnapshotKind, LoadOutcome>>>, CompletableFuture<Snapshot<Compiled, Compiled>>> {
        val answer = load()
        answer.isDone.assert().isFalse()
        return answer.thenApply { metrics.loads.toList() } to answer
    }

    private fun policiesOutcomes() = metrics.loads.filter { it.first == SnapshotKind.POLICIES }.map { it.second }

    @Test
    fun coldLoadMetricIsRecordedBeforeTheAnswer() {
        testSource(store, metrics = metrics, scheduler = scheduler).use { source ->
            val (seen, answer) = source.loadObserved()
            store.releaseAll()

            seen.join().assert().containsExactly(SnapshotKind.POLICIES to LoadOutcome.COLD)
            answer.join().policies.assert().isInstanceOf(DocumentState.Present::class.java)
        }
        scheduler.shutdownNow()
    }

    @Test
    fun timeoutMetricIsRecordedBeforeTheAnswerAndFailsClosed() {
        testSource(store, metrics = metrics, scheduler = scheduler).use { source ->
            val (seen, answer) = source.loadObserved()
            scheduler.fire()

            seen.join().assert().containsExactly(SnapshotKind.POLICIES to LoadOutcome.TIMEOUT)
            answer.join().policies.assert().isInstanceOf(DocumentState.Failed::class.java)

            store.releaseAll()
            policiesOutcomes().assert().containsExactly(LoadOutcome.TIMEOUT)
            source.peek().assert().isNotNull()
        }
        scheduler.shutdownNow()
    }

    @Test
    fun failedLoadMetricIsRecordedBeforeTheAnswerAndFailsClosed() {
        store.failure = IllegalStateException("redis down")
        testSource(store, metrics = metrics, scheduler = scheduler).use { source ->
            val (seen, answer) = source.loadObserved()
            store.releaseAll()

            seen.join().assert().containsExactly(SnapshotKind.POLICIES to LoadOutcome.FAILED)
            answer.join().policies.assert().isInstanceOf(DocumentState.Failed::class.java)
        }
        scheduler.shutdownNow()
    }

    @Test
    fun timeoutThatCannotBeScheduledFailsClosedOnce() {
        val stopped = ScheduledThreadPoolExecutor(1).apply { shutdown() }
        testSource(store, metrics = metrics, scheduler = stopped).use { source ->
            val answer = source.load()

            answer.isDone.assert().isTrue()
            answer.join().policies.assert().isInstanceOf(DocumentState.Failed::class.java)
            policiesOutcomes().assert().containsExactly(LoadOutcome.FAILED)

            store.releaseAll()
            policiesOutcomes().assert().containsExactly(LoadOutcome.FAILED)
        }
    }

    @Test
    fun racingTimeoutAndLoadAnswerOnceWithOneMetric() {
        val barrier = CyclicBarrier(2)
        repeat(500) { iteration ->
            val racing = ControllableStore().apply {
                put(SnapshotKey.Policies, document(1))
                hold = true
            }
            val recorded = RecordingMetrics()
            val timeouts = ManualScheduler()
            testSource(racing, metrics = recorded, scheduler = timeouts).use { source ->
                val answer = source.load()
                val timeout = CompletableFuture.runAsync {
                    barrier.await()
                    timeouts.fire()
                }
                barrier.await()
                racing.releaseAll()
                timeout.join()

                val outcomes = recorded.loads.filter { it.first == SnapshotKind.POLICIES }.map { it.second }
                outcomes.assert().describedAs("iteration $iteration").hasSize(1)
                val policies = answer.join().policies
                when (outcomes.single()) {
                    LoadOutcome.COLD -> policies.assert().isInstanceOf(DocumentState.Present::class.java)
                    LoadOutcome.TIMEOUT -> policies.assert().isInstanceOf(DocumentState.Failed::class.java)
                    else -> error("unexpected outcome ${outcomes.single()} in iteration $iteration")
                }
                source.peek().assert().isNotNull()
            }
            timeouts.shutdownNow()
        }
    }
}
