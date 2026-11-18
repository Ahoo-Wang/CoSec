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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What one uncontended cold load costs in thread work (RFC 0013 B2b, the cold-load criterion): one compile-pool task,
 * one housekeeping task (the eviction pass of a family beyond its bound), one timer scheduled and cancelled, and one
 * completion that crosses from the pool to the caller's thread, and nothing more: no other hand-off, timer or wake-up,
 * on any host. The counts are deterministic, so this holds where timing cannot show it.
 *
 * The source's executors are driven by the test: the compilation runs on a thread of the test's own, marked as the
 * source's pool threads are, after `load` returned; housekeeping runs on another, and the next load waits for it.
 */
class ColdLoadCountsTest {
    private class Counts {
        val poolTasks = AtomicInteger()
        val housekeepingTasks = AtomicInteger()
        val timersScheduled = AtomicInteger()
        val timersCancelled = AtomicInteger()
        val crossThreadCompletions = AtomicInteger()

        fun perLoad(loads: Int) = listOf(
            poolTasks,
            housekeepingTasks,
            timersScheduled,
            timersCancelled,
            crossThreadCompletions
        )
            .map { it.get().toDouble() / loads }
    }

    private fun measure(runners: KeyRunner.Settings): List<Double> {
        val counts = Counts()
        val store = ControllableStore().apply {
            put(SnapshotKey.Platform, document(1))
            KEYS.forEach { put(it, document(1)) }
        }
        val pool = Executors.newSingleThreadExecutor { Thread(it, "counts-pool") }
        val housekeepingThread = Executors.newSingleThreadExecutor { Thread(it, "counts-housekeeping") }
        val compileTasks = ConcurrentLinkedQueue<Runnable>()
        val counting = AtomicBoolean()
        lateinit var threads: SnapshotThreads
        val housekeeping = object : AbstractExecutorService() {
            override fun execute(command: Runnable) {
                if (counting.get()) counts.housekeepingTasks.incrementAndGet()
                housekeepingThread.execute {
                    threads.onOwnThread.set(true)
                    threads.drainsUnbounded.set(true)
                    command.run()
                }
            }

            override fun shutdown() = Unit
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown(): Boolean = false
            override fun isTerminated(): Boolean = true
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        }
        val realScheduler = ScheduledThreadPoolExecutor(1)
        // The timers a counted load scheduled, the futures themselves: after the load, those cancelled are counted.
        val timers = ConcurrentLinkedQueue<ScheduledFuture<*>>()
        val scheduler = object : ScheduledExecutorService by realScheduler {
            private fun <V> counted(future: ScheduledFuture<V>): ScheduledFuture<V> {
                if (counting.get()) {
                    counts.timersScheduled.incrementAndGet()
                    timers += future
                }
                return future
            }

            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                counted(realScheduler.schedule(command, delay, unit))

            override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
                counted(realScheduler.schedule(callable, delay, unit))
        }
        val compiler = FakeCompiler()
        val source = SnapshotSource(
            store,
            SnapshotKey.Platform,
            compiler,
            listOf(SnapshotFamily(SnapshotKind.APP, compiler, OPTIONS.maxApps, OPTIONS.maxAbsent)),
            OPTIONS,
            ManualClock(),
            SnapshotMetrics.NOOP,
            { task ->
                if (counting.get()) counts.poolTasks.incrementAndGet()
                compileTasks += task
            },
            scheduler,
            // The eviction's order: the least recently used key goes, so each key of the cycle is cold again.
            AtomicLong()::incrementAndGet,
            housekeeping,
            runners,
        )
        threads = source.threads
        try {
            fun coldLoad(key: SnapshotKey.App) {
                val answer = source.load(key.appId)
                answer.isDone.assert().describedAs("$key answered inside its offer").isFalse()
                val requester = Thread.currentThread()
                answer.whenComplete { _, _ -> if (counting.get() && Thread.currentThread() !== requester) counts.crossThreadCompletions.incrementAndGet() }
                while (!answer.isDone) {
                    val task = compileTasks.poll() ?: continue
                    pool.submit {
                        threads.onOwnThread.set(true)
                        threads.drainsUnbounded.set(true)
                        task.run()
                    }.get()
                }
                answer.join()
                // The next load starts with nothing of this one in motion.
                pool.submit {}.get()
                housekeepingThread.submit {}.get()
                counts.timersCancelled.addAndGet(timers.count { it.isCancelled })
                timers.clear()
                compileTasks.assert().isEmpty()
            }
            val shared = source.load()
            while (!shared.isDone) compileTasks.poll()?.run()
            // Every key once, so each is seen: a cold load reads the store of record, as in the benchmark.
            repeat(2) { KEYS.forEach(::coldLoad) }
            counting.set(true)
            KEYS.forEach(::coldLoad)
            counting.set(false)
        } finally {
            source.close()
            pool.shutdownNow()
            housekeepingThread.shutdownNow()
            realScheduler.shutdownNow()
        }
        return counts.perLoad(KEYS.size)
    }

    @Test
    fun `an uncontended cold load costs the runner one pool task, one housekeeping task, one timer and one cross-thread answer`() {
        measure(keyRunners()).assert().containsExactly(1.0, 1.0, 1.0, 1.0, 1.0)
    }

    private companion object {
        /** More keys than the family holds, so every load is cold and every install takes the family past its bound. */
        val KEYS = List(24) { SnapshotKey.App("app-$it") }
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = 8)
    }
}
