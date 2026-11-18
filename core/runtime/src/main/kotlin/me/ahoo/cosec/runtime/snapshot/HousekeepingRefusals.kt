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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * What housekeeping refused since its last summary (`SNP-19`): a count per [HousekeepingTask] and the text of the last
 * one dropped, and the meters that threw ([GuardedSnapshotMetrics]). The source's own plain state, never read back from
 * [SnapshotMetrics]: the summary is written whatever the metrics are. Recording costs a caller's thread one increment,
 * one write and a line of at most [MAX_LINE] characters built without I/O.
 */
internal class HousekeepingRefusals {
    private val counts = AtomicLongArray(HousekeepingTask.entries.size)
    private val lastLines = AtomicReferenceArray<String>(HousekeepingTask.entries.size)
    private val meterFailures = AtomicLong()

    @Volatile
    private var lastMeterFailure: String? = null

    /** When the last summary was taken, by monotonic time; none yet while null. */
    @Volatile
    private var lastSummary: Long? = null

    fun refused(task: HousekeepingTask, line: String) {
        lastLines.set(task.ordinal, line.take(MAX_LINE))
        counts.incrementAndGet(task.ordinal)
    }

    /** A meter threw: counted and kept for the summary, never offered to housekeeping (a meter's own refusal would loop). */
    fun meterFailed(event: String, error: Throwable) {
        lastMeterFailure = line(event, error)
        meterFailures.incrementAndGet()
    }

    /**
     * The summary line since the last one, taken by housekeeping itself (the lag report's tick): null when nothing moved,
     * or, unless [force]d (the source is closing), when one was taken less than [INTERVAL_NANOS] ago, and then what moved
     * waits for the next. Taking it resets the counts.
     */
    fun summary(nanos: Long, force: Boolean = false): String? {
        val last = lastSummary
        if (!force && last != null && nanos - last < INTERVAL_NANOS) {
            return null
        }
        val taken = LongArray(HousekeepingTask.entries.size) { counts.getAndSet(it, 0) }
        val meters = meterFailures.getAndSet(0)
        if (taken.all { it == 0L } && meters == 0L) {
            return null
        }
        lastSummary = nanos
        val tasks = HousekeepingTask.entries.filter { taken[it.ordinal] > 0 }.joinToString(", ") { task ->
            "${task.tag} ${taken[task.ordinal]} (last: \"${lastLines.get(task.ordinal)}\")"
        }
        val parts = listOfNotNull(
            if (tasks.isEmpty()) {
                null
            } else {
                "refused ${taken.sum()} tasks, full or stopped (each dropped; drop runs in place): $tasks"
            },
            if (meters == 0L) null else "meter failures $meters (last: \"$lastMeterFailure\")",
        )
        return "Snapshot housekeeping since its last summary: ${parts.joinToString("; ")}."
    }

    companion object {
        /** The longest text kept per task: bounds what a refusal builds on a caller's thread. */
        const val MAX_LINE = 256

        /** Summaries are at least this far apart. */
        val INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10)

        /** [message], and [cause]'s class and message when there is one, cut at [MAX_LINE]: no stack is walked. */
        fun line(message: String, cause: Throwable?): String =
            (if (cause == null) message else "$message — ${cause.javaClass.name}: ${cause.message}").take(MAX_LINE)
    }
}
