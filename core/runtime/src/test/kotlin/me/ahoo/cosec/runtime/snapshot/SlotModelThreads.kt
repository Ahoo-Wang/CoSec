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

import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier

/**
 * Every thread a [SnapshotSource] uses, run by the test on its own thread in an order it scripts (RFC 0013 B2b gate 1):
 * the compile pool and housekeeping are queues the script takes tasks from, and the scheduler keeps virtual time, which
 * moves only when the script advances it, with the wall clock and `nanoTime` together.
 */
internal class SlotModelThreads {
    val clock = ManualClock()

    /** Virtual monotonic time: [SnapshotSource.nanoTime], and what the scheduler's tasks are due at. */
    var nanos = 0L
        private set

    val nanoTime = LongSupplier { nanos }

    /** What a task threw that a thread pool would have swallowed or died of: a [VirtualMachineError] above all. */
    val thrown = mutableListOf<Throwable>()

    val compiles = ArrayList<Runnable>()

    /** While true, the compile pool rejects new tasks, as a full queue does. */
    var rejectCompiles = false

    /** How many tasks the compile pool rejected. */
    var rejected = 0
        private set

    val compileExecutor = Executor { task ->
        if (rejectCompiles) {
            rejected++
            throw RejectedExecutionException("the scripted compile queue is full")
        }
        compiles += task
    }

    val housekeeping = Housekeeping()

    val scheduler = Scheduler()

    fun runCompile(index: Int) {
        run(compiles.removeAt(index))
    }

    fun runHousekeeping(index: Int) {
        run(housekeeping.tasks.removeAt(index))
    }

    /** Runs before a periodic task (the revalidation pass) runs: what the model counts as a pass that may read anything. */
    var onPeriodic: () -> Unit = {}

    /**
     * Moves time by [duration], running each task that falls due on the way, in the order they fall due; without
     * [periodic], the periodic ones stay due and run at the next move that takes them.
     */
    fun advance(duration: Duration, periodic: Boolean = true) {
        val until = nanos + duration.toNanos()
        while (true) {
            val due = scheduler.nextDue(periodic) ?: break
            if (due.at > until) break
            moveTo(due.at)
            if (due.period != 0L) onPeriodic()
            scheduler.fire(due)
        }
        moveTo(until)
    }

    /** When the next task that is not periodic falls due, or null when none is waiting. */
    fun nextOneShot(): Long? = scheduler.timers.filter { it.period == 0L }.minOfOrNull { it.at }

    private fun moveTo(at: Long) {
        if (at > nanos) {
            clock.advance(Duration.ofNanos(at - nanos))
            nanos = at
        }
    }

    private fun run(task: Runnable) {
        @Suppress("TooGenericExceptionCaught")
        try {
            task.run()
        } catch (error: Throwable) {
            thrown += error
        }
    }

    /** The housekeeping thread: a queue; shut down, it takes nothing more, and waiting for it runs what it holds. */
    inner class Housekeeping : AbstractExecutorService() {
        val tasks = ArrayList<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("housekeeping is shut down")
            tasks += command
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return ArrayList(tasks).also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        /** The thread finishes what is queued: here, on the caller's thread, in queue order. */
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            while (tasks.isNotEmpty()) run(tasks.removeAt(0))
            return shutdown
        }
    }

    /** A timer of the scheduler: due [at], again every [period] nanoseconds after it ran when that is not zero. */
    inner class Timer(var at: Long, val period: Long, val sequence: Long, task: Runnable) :
        FutureTask<Any?>(task, null), RunnableScheduledFuture<Any?> {
        override fun isPeriodic(): Boolean = period != 0L

        override fun getDelay(unit: TimeUnit): Long = unit.convert(at - nanos, TimeUnit.NANOSECONDS)

        /** By when it falls due, then by when it was set: 0 only for the same timer, as [equals] is identity. */
        override fun compareTo(other: Delayed): Int {
            val byDelay = compareValues(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS))
            return if (byDelay != 0 || other !is Timer) byDelay else compareValues(sequence, other.sequence)
        }

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            scheduler.timers.remove(this)
            return super.cancel(mayInterruptIfRunning)
        }

        override fun run() {
            if (!isPeriodic) {
                super.run()
            } else if (runAndReset()) {
                at = nanos + period
                scheduler.timers += this
            }
        }

        public override fun setException(t: Throwable) {
            thrown += t
            super.setException(t)
        }
    }

    /** The scheduler: the timers the source sets, in virtual time; only the calls a source makes are implemented. */
    inner class Scheduler : AbstractExecutorService(), ScheduledExecutorService {
        val timers = ArrayList<Timer>()
        private var sequence = 0L

        fun nextDue(periodic: Boolean = true): Timer? =
            timers.filter { periodic || it.period == 0L }.minWithOrNull(compareBy<Timer>({ it.at }, { it.sequence }))

        fun fire(timer: Timer) {
            timers.remove(timer)
            run(timer)
        }

        private fun add(delay: Long, unit: TimeUnit, period: Long, task: Runnable): Timer =
            Timer(
                nanos + unit.toNanos(delay).coerceAtLeast(0),
                unit.toNanos(period),
                sequence++,
                task
            ).also { timers += it }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> = add(
            delay,
            unit,
            0,
            command
        )

        override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            throw UnsupportedOperationException("not a call a snapshot source makes")

        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("not a call a snapshot source makes")

        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            add(initialDelay, unit, delay, command)

        override fun execute(command: Runnable) = throw UnsupportedOperationException(
            "not a call a snapshot source makes"
        )

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    }
}
