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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Acknowledgement
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ShadowState
import me.ahoo.cosec.control.change.ShadowSummary
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReadings
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Instant

/**
 * The shadow windows of staged changes (RFC 0007 §6.4) and the propagation of published revisions (§6.5), from what the
 * instances report through [reports].
 */
@InternalCoSecApi
class ShadowWindows(
    private val reports: ShadowReportReader,
    private val options: ShadowOptions = ShadowOptions(),
    clock: Clock = Clock.systemUTC(),
) {
    private val statuses = InstanceStatuses(reports, clock)

    /**
     * The summary of [state] at [now] when the window may close, or null while it stays open: an instance could not
     * compile or evaluate the candidate (closes at once, blocked); enough time and samples (closes); the longest window
     * (closes, insufficient).
     */
    suspend fun close(state: ShadowState, now: Instant): ShadowSummary? = look(state, now).summary

    /**
     * What the instances report of [state]'s window at [now]: every instance seen so far, and the summary when the
     * window may close (see [close]).
     *
     * The total is the sum of one report per instance, each that instance's cumulative report (review R1). Anything
     * else is an error that keeps the window open, never a sample: a report of another window, a second report of one
     * instance, or a negative count. An instance blocks the window by itself: one with a `CANDIDATE_ERROR` blocks it
     * whatever the others report.
     *
     * An instance [ShadowState.reporters] says reported before and that has no report now has vanished, and so has one
     * whose listed report cannot be read ([ShadowReadings.unreadable]): its evidence is missing, never zero, since the instance that vanishes is as likely as any to be the one that saw the narrowed
     * requests (`CTL-56`). The window waits for it; an instance rewrites its cumulative report every few seconds, so a
     * report lost in a failover comes back while the instance serves. At the longest window it closes insufficient,
     * naming the vanished instances, and never passes.
     *
     * A window passes only when every live instance acknowledges it ([acknowledgement], `CTL-10`): one that never saw the
     * window, or never compiled its candidate, holds it open whatever the samples say, and at the longest window it
     * closes insufficient. An acknowledgement adds no sample. A `CANDIDATE_ERROR` still blocks it at once.
     */
    suspend fun look(state: ShadowState, now: Instant): ShadowLook {
        val window = state.window
        val readings = reports.readings(window)
        val read = readings.reports
        val foreign = read.any { it.window != window }
        check(!foreign) { "A report read for window $window is of another window" }
        val twice = read.groupingBy { it.instance }.eachCount().filterValues { it > 1 }.keys
        check(twice.isEmpty()) { "Instances ${twice.sorted()} reported more than once on window $window" }
        val negative = read.any { report -> report.counts.values.any { it < 0 } }
        check(!negative) { "A report of window $window has a negative count" }
        val reporters = read.map { it.instance }.toSet()
        // A listed report that cannot be read is missing evidence too: that instance has reported, and must again.
        val vanished = state.reporters - reporters + readings.unreadable
        val counts = ShadowOutcome.entries.associate { it.name to 0L }.toMutableMap()
        for (report in read) {
            report.counts.forEach { (outcome, count) -> counts.merge(outcome.name, count, Long::plus) }
        }
        val blocked = read.any { (it.counts[ShadowOutcome.CANDIDATE_ERROR] ?: 0) > 0 }
        val effective = ShadowOutcome.entries.filter { it.effective }.sumOf { counts.getValue(it.name) }
        val elapsed = java.time.Duration.between(state.startedAt, now)
        val acknowledgement = acknowledgement(window)
        val acknowledged = acknowledgement.waitingFor.isEmpty()
        val summary = when {
            blocked -> ShadowSummary(counts, now, insufficient = false, blocked = true, vanished = vanished)
            vanished.isNotEmpty() && elapsed < options.maxDuration -> null
            vanished.isEmpty() && acknowledged && elapsed >= options.minDuration && effective >= options.minSamples ->
                ShadowSummary(counts, now, insufficient = false, blocked = false)
            elapsed >= options.maxDuration -> ShadowSummary(counts, now, insufficient = true, blocked = false, vanished)
            else -> null
        }
        return ShadowLook(state.reporters + reporters + readings.unreadable, summary, vanished, acknowledgement)
    }

    /**
     * Which live instances acknowledge [window] (design D2 §5, `CTL-10`): of those with a status in the last minute, read
     * as `propagation` reads them (one read shared, at most every [InstanceStatuses.MAX_AGE]) and taking part in shadowing
     * ([me.ahoo.cosec.spi.shadow.InstanceStatus.shadows]), those whose status lists it. One whose status has no `windows` (an older version, or one holding no staged list) acknowledges nothing. A
     * status read that fails fails the call: a window is never taken as acknowledged by statuses nobody read.
     */
    suspend fun acknowledgement(window: String): Acknowledgement {
        // An instance that does not shadow (its candidate source is not CoSec's) is not waited for; propagation counts it.
        val live = statuses.read().statuses.filter { it.shadows }
        val waiting = live.filter { it.windows?.contains(window) != true }.map { it.instance }.sorted()
        return Acknowledgement(live.size - waiting.size, waiting)
    }

    /**
     * Read-only propagation (RFC 0007 §6.5, Q29, `CTL-62`): of the instances that wrote a status in the last minute
     * ([Propagation.recent]), those that hold [key] at any revision ([Propagation.holding]) and those of them that have
     * installed [revision] or a later one ([Propagation.installed]). An instance keeps resident only the tenants it
     * serves, so no instance is behind when [Propagation.installed] equals [Propagation.holding]; `holding: 0` means no
     * instance holds the document, and the first request that needs it loads it at the published revision. The
     * statuses are read at most every [InstanceStatuses.MAX_AGE], and the answer says when ([Propagation.asOf]).
     * Publishing never waits for it.
     */
    suspend fun propagation(key: SnapshotKey, revision: Long): Propagation {
        val read = statuses.read()
        val holding = read.statuses.mapNotNull { it.documents[key] }
        return Propagation(
            revision,
            holding.count { it.revision >= revision },
            holding.size,
            read.statuses.size,
            read.asOf
        )
    }
}
