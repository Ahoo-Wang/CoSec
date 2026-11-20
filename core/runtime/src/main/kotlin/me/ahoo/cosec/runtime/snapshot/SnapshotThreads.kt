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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SnapshotExecutors.newCompileExecutor
import me.ahoo.cosec.runtime.snapshot.SnapshotExecutors.newHousekeeping
import me.ahoo.cosec.runtime.snapshot.SnapshotExecutors.newScheduler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/**
 * The threads of a [SnapshotSource] (RFC 0013 B2a): its compile pool and scheduler, each given or started for itself,
 * and the housekeeping thread it starts unless a test gives one ([SnapshotExecutors]). Compilation runs on the compile
 * pool; the scheduler thread only keeps time; eviction, lag reports and log lines with stack traces run on
 * housekeeping, so a slow log appender cannot delay a timeout (#1252).
 *
 * The steps that stop them are separate, not one `close()`: the source interleaves them with its own steps, in an order
 * that matters ([SnapshotSource.close]), and a constructor that fails releases them as it does ([SnapshotSource]).
 */
internal class SnapshotThreads(
    options: SnapshotOptions,
    compileExecutor: Executor?,
    scheduler: ScheduledExecutorService?,
    /** Tests only: what runs housekeeping in place of the thread started here; shut down and awaited as that one is. */
    housekeeping: ExecutorService?,
    /** The source's metrics, already guarded: a refusal is reported to them. */
    private val metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
    /** What housekeeping refused since its last summary, and the meters that threw (`SNP-19`). */
    val refusals: HousekeepingRefusals = HousekeepingRefusals(),
) {

    /** Set on the threads this source starts: [SnapshotSource.close] does not wait for the thread it runs on. */
    val onOwnThread = ThreadLocal<Boolean>()

    /**
     * Set on the compile pool's and housekeeping's threads only, of those this source starts: the ones that may drain a
     * key without a bound, scan a family in place, and have a key's `VirtualMachineError` rethrown into them (RFC 0013
     * B2b §3). Not the scheduler's: it keeps time, and drains as a caller does.
     */
    val drainsUnbounded = ThreadLocal<Boolean>()
    private val ownsCompileExecutor = compileExecutor == null
    val compileExecutor: Executor = compileExecutor ?: newCompileExecutor(options, onOwnThread, drainsUnbounded)
    private val ownsScheduler = scheduler == null
    val scheduler: ScheduledExecutorService = scheduler ?: newScheduler(onOwnThread)
    val housekeeping: ExecutorService = housekeeping ?: newHousekeeping(onOwnThread, drainsUnbounded)

    /**
     * [nanos], less a random part of up to half of it, on the scheduler this source started: what spreads the cold
     * compile retries of a burst (R2D-4). A scheduler a test gives keeps virtual time, which a scripted run replays: it
     * gets [nanos] as it is.
     */
    fun spread(
        nanos: Long
    ): Long = if (ownsScheduler) nanos - ThreadLocalRandom.current().nextLong(nanos / 2 + 1) else nanos

    /** Stops the scheduler, when the source started it: nothing is timed any more. */
    fun stopScheduler() {
        if (ownsScheduler) scheduler.shutdownNow()
    }

    /** Lets the housekeeping thread finish what is queued, and take nothing more. */
    fun stopHousekeeping() {
        housekeeping.shutdown()
    }

    /** Lets the compile pool, when the source started it, finish what is queued, and take nothing more. */
    fun stopCompiling() {
        if (ownsCompileExecutor) (compileExecutor as ThreadPoolExecutor).shutdown()
    }

    /**
     * Waits, at most [timeout], until the threads this source started have finished what was already queued on them:
     * once [SnapshotSource.close] returns, a closed source does no more work and leaves no thread behind. Without the
     * wait, a host that closes and replaces sources faster than queued work drains piles their threads up (RFC 0008
     * §3.5's property stress reached the per-process thread limit). Not on one of those threads, which would wait for
     * itself. Then, whatever was refused since the last summary is written once, on this thread, whatever the interval
     * says (`SNP-19`): housekeeping, which would write it, is gone.
     */
    fun awaitOwned(timeout: Duration) {
        try {
            awaitThreads(timeout)
        } finally {
            refusals.summary(System.nanoTime(), force = true)?.let { line -> log.warn { line } }
        }
    }

    private fun awaitThreads(timeout: Duration) {
        if (onOwnThread.get() == true) {
            return
        }
        val owned = buildList {
            if (ownsScheduler) add(scheduler)
            add(housekeeping)
            if (ownsCompileExecutor) add(compileExecutor as ExecutorService)
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            for (executor in owned) {
                val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
                if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    log.warn { "The snapshot source's threads did not finish within $timeout of closing." }
                    return
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Tests only: housekeeping has nothing queued and nothing running (a test's own housekeeping counts as idle). */
    val housekeepingIdle: Boolean
        get() = (housekeeping as? ThreadPoolExecutor)?.let { it.queue.isEmpty() && it.activeCount == 0 } ?: true

    /** Whether every thread this source started has finished. */
    val terminated: Boolean
        get() = (!ownsScheduler || scheduler.isTerminated) && housekeeping.isTerminated &&
            (!ownsCompileExecutor || (compileExecutor as ExecutorService).isTerminated)

    /**
     * Runs [task] on the housekeeping thread, for what the scheduler thread must not do itself. Never in place, since
     * nothing may hold up a timeout: false when the thread has no room, and [task] is dropped.
     */
    fun offTimer(task: () -> Unit): Boolean = try {
        housekeeping.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    /**
     * Runs [task] on housekeeping, off every caller's thread and off the timer (`SNP-19`). When housekeeping refuses it,
     * full or stopped, [kind] decides: a log line, an eviction pass or a lag report is dropped and counted, with [line]
     * kept for the summary; an empty key's drop ([HousekeepingTask.runsInPlace]) runs here, one offer to its own drain.
     * Nothing is written on a caller's thread. Once housekeeping is stopped, on one of this source's own threads the task
     * runs in place, uncounted: that thread is no caller's, and closing loses nothing.
     */
    fun offHousekeeping(kind: HousekeepingTask, line: () -> String, task: () -> Unit) {
        try {
            housekeeping.execute(task)
        } catch (_: RejectedExecutionException) {
            notOffered(kind, line, task)
        }
    }

    /**
     * As above, for a task whose line names only [key]: nothing is built for it unless it is refused. False when it was
     * not taken, for a caller that offers it again later (UC-1's reviews); a task that then ran in place, at close or as
     * [HousekeepingTask.runsInPlace], answers false too.
     */
    fun offHousekeeping(kind: HousekeepingTask, key: SnapshotKey, task: () -> Unit): Boolean =
        try {
            housekeeping.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            notOffered(kind, { kind.about(key) }, task)
            false
        }

    private fun notOffered(kind: HousekeepingTask, line: () -> String, task: () -> Unit) {
        if (housekeeping.isShutdown && onOwnThread.get() == true) {
            task()
            return
        }
        refused(kind, line)
        if (kind.runsInPlace) task()
    }

    /** A [kind] of task housekeeping refused: counted for the summary, with [line], and reported to the metrics. */
    fun refused(kind: HousekeepingTask, line: () -> String) {
        refusals.refused(kind, line())
        metrics.onHousekeepingRefused(kind)
    }
}
