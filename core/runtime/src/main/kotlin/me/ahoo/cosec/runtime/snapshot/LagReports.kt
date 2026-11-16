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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/**
 * The lag reports of a [SnapshotSource] (RFC 0013 B2a): each tick queues one on the housekeeping thread, which reports
 * every document of [entries] lagging past [SnapshotOptions.lagWarn], at most once per
 * [SnapshotOptions.lagLogInterval] for each.
 */
internal class LagReports(
    private val options: SnapshotOptions,
    private val clock: Clock,
    private val metrics: SnapshotMetrics,
    private val threads: SnapshotThreads,
    private val entries: () -> List<SnapshotEntry<*>>,
) {
    private val lastLagReport = ConcurrentHashMap<SnapshotKey, Instant>()

    /** Whether a lag report waits on the housekeeping thread: a stalled appender must not pile up one per tick. */
    private val lagReportQueued = AtomicBoolean()

    /** Forgets when [key] was last reported: an evicted document reports its lag afresh once it is back. */
    fun forget(key: SnapshotKey) {
        lastLagReport.remove(key)
    }

    /** At most one report waits on the housekeeping thread; a tick that finds it there, or no room, leaves it to the next. */
    fun queue() {
        if (!lagReportQueued.compareAndSet(false, true)) {
            return
        }
        val queued = threads.offTimer {
            lagReportQueued.set(false)
            report()
        }
        if (!queued) {
            lagReportQueued.set(false)
        }
    }

    /**
     * Reports every document lagging past [SnapshotOptions.lagWarn], at most once per [SnapshotOptions.lagLogInterval].
     * Off the scheduler thread: a log appender or a meter may take its time here.
     */
    fun report() {
        val now = clock.instant()
        for (entry in entries()) {
            if (entry.state is DocumentState.Failed) {
                continue
            }
            val lag = entry.lagAt(now)
            val escalated = lag > options.lagEscalate
            if (!escalated && lag <= options.lagWarn) {
                lastLagReport.remove(entry.key)
                continue
            }
            if (!due(entry.key, now)) {
                continue
            }
            metrics.onLagExceeded(entry.key, lag, escalated)
            if (escalated) {
                log.error { "Snapshot document [${entry.key}] revision ${entry.revision} is stale: not confirmed for $lag." }
            } else {
                log.warn { "Snapshot document [${entry.key}] revision ${entry.revision} has not been confirmed for $lag." }
            }
        }
    }

    private fun due(key: SnapshotKey, now: Instant): Boolean {
        var due = false
        lastLagReport.compute(key) { _, last ->
            if (last != null && Duration.between(last, now) < options.lagLogInterval) {
                last
            } else {
                due = true
                now
            }
        }
        return due
    }
}
