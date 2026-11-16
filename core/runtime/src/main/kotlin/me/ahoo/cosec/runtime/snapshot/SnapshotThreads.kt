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
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
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
) {
    /** Set on the threads this source starts: [SnapshotSource.close] does not wait for the thread it runs on. */
    val onOwnThread = ThreadLocal<Boolean>()
    private val ownsCompileExecutor = compileExecutor == null
    val compileExecutor: Executor = compileExecutor ?: newCompileExecutor(options, onOwnThread)
    private val ownsScheduler = scheduler == null
    val scheduler: ScheduledExecutorService = scheduler ?: newScheduler(onOwnThread)
    val housekeeping: ExecutorService = housekeeping ?: newHousekeeping(onOwnThread)

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
     * itself.
     */
    fun awaitOwned(timeout: Duration) {
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

    /** Whether every thread this source started has finished. */
    val terminated: Boolean
        get() = (!ownsScheduler || scheduler.isTerminated) && housekeeping.isTerminated &&
            (!ownsCompileExecutor || (compileExecutor as ExecutorService).isTerminated)

    /**
     * Runs [task] on the housekeeping thread, for what the scheduler thread must not do itself. Never in place, unlike
     * [offRequestThread], since nothing may hold up a timeout: false when the thread has no room, and [task] is dropped.
     */
    fun offTimer(task: () -> Unit): Boolean = try {
        housekeeping.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    /** Runs [task] on the housekeeping thread, off every request thread and off the timer; in place if it is gone. */
    fun offRequestThread(task: () -> Unit) {
        try {
            housekeeping.execute(task)
        } catch (rejected: RejectedExecutionException) {
            log.debug { "Housekeeping rejected a task ($rejected); running it in place." }
            task()
        }
    }
}
