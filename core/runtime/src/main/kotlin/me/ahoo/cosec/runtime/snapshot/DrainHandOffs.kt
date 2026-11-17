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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The drains a [SnapshotSource]'s keys hand off when a thread's budget is spent (RFC 0013 B2b §3): one task in the
 * compile pool at a time, which runs them in order, so that hand-offs never compete with compilations one task per
 * key. Scheduled by a CAS, like a drain: a hand-off made while the task runs is taken by it, or by the task scheduled
 * after it. A pool that refuses the task sends it to housekeeping. When that refuses too, the caller's thread runs the
 * source's hand-offs, for as long as they come: the back-pressure that keeps answering under overload, where deferring
 * them would leave requests waiting without a bound. Each refusal is counted ([SnapshotMetrics.onHandOffRefused]).
 *
 * Close owns what the source accepted, and a hand-off may hold a key close does not reach: a removed one, whose
 * requests a pass or a lane offered after its eviction. So close runs what is pending ([runPending]) once its keys are
 * ended, and a hand-off made after that is run by its offerer, which re-checks [closed] once it has enqueued.
 */
internal class DrainHandOffs(
    private val threads: SnapshotThreads,
    private val metrics: SnapshotMetrics,
    private val closed: AtomicBoolean,
) {
    private val waiting = ConcurrentLinkedQueue<Runnable>()
    private val scheduled = AtomicBoolean()

    fun handOff(drain: Runnable) {
        waiting.add(drain)
        schedule()
        // Enqueue, then re-check: close set [closed] before it ran what was pending, so either it took this one or this
        // offerer sees [closed] and takes it, whatever the pool did with the task.
        if (closed.get()) runPending()
    }

    /**
     * Runs every pending hand-off on this thread, unbounded: close's, once its keys are ended, and an offerer's after.
     * One that throws does not leave the rest queued with their waiters: they all run, and the first failure is thrown
     * at the end with the others suppressed in it, as [SnapshotSource.close] does with its steps.
     */
    @Suppress("TooGenericExceptionCaught")
    fun runPending() {
        var failure: Throwable? = null
        while (true) {
            val drain = waiting.poll() ?: break
            try {
                drain.run()
            } catch (error: Throwable) {
                failure?.addSuppressed(error)
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            threads.compileExecutor.execute(::run)
        } catch (_: RejectedExecutionException) {
            val housekept = threads.offTimer(::run)
            metrics.onHandOffRefused(ranInPlace = !housekept)
            // Both refused: this caller's thread runs the source's hand-offs, for as long as they come.
            if (!housekept) run()
        }
    }

    private fun run() {
        try {
            while (true) {
                (waiting.poll() ?: break).run()
            }
        } finally {
            scheduled.set(false)
            // A hand-off made between the last poll and the release is taken by the task this schedules.
            if (waiting.isNotEmpty()) schedule()
        }
    }
}
