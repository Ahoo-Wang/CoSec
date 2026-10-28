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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowReportReader
import me.ahoo.cosec.snapshot.SnapshotKey
import java.time.Instant

/**
 * The shadow windows of staged changes (RFC 0007 §6.4) and the propagation of published revisions (§6.5), from what the
 * instances report through [reports].
 */
class ShadowWindows(private val reports: ShadowReportReader, private val options: ShadowOptions = ShadowOptions()) {
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
     * instance, a negative count, or no report from an instance [ShadowState.reporters] says reported before, since the
     * instance that vanishes is as likely as any to be the one that saw errors. An instance blocks the window by
     * itself: one with a `CANDIDATE_ERROR` blocks it whatever the others report.
     */
    suspend fun look(state: ShadowState, now: Instant): ShadowLook {
        val window = state.window
        val read = reports.reports(window)
        val foreign = read.any { it.window != window }
        check(!foreign) { "A report read for window $window is of another window" }
        val twice = read.groupingBy { it.instance }.eachCount().filterValues { it > 1 }.keys
        check(twice.isEmpty()) { "Instances ${twice.sorted()} reported more than once on window $window" }
        val negative = read.any { report -> report.counts.values.any { it < 0 } }
        check(!negative) { "A report of window $window has a negative count" }
        val reporters = read.map { it.instance }.toSet()
        val vanished = state.reporters - reporters
        check(vanished.isEmpty()) { "Instances ${vanished.sorted()} reported on window $window and have no report now" }
        val counts = ShadowOutcome.entries.associate { it.name to 0L }.toMutableMap()
        for (report in read) {
            report.counts.forEach { (outcome, count) -> counts.merge(outcome.name, count, Long::plus) }
        }
        val blocked = read.any { (it.counts[ShadowOutcome.CANDIDATE_ERROR] ?: 0) > 0 }
        val effective = ShadowOutcome.entries.filter { it.effective }.sumOf { counts.getValue(it.name) }
        val elapsed = java.time.Duration.between(state.startedAt, now)
        val summary = when {
            blocked -> ShadowSummary(counts, now, insufficient = false, blocked = true)
            elapsed >= options.minDuration && effective >= options.minSamples ->
                ShadowSummary(counts, now, insufficient = false, blocked = false)
            elapsed >= options.maxDuration -> ShadowSummary(counts, now, insufficient = true, blocked = false)
            else -> null
        }
        return ShadowLook(state.reporters + reporters, summary)
    }

    /**
     * Read-only propagation (RFC 0007 §6.5, Q29): of the instances that wrote a status in the last minute, how many have
     * installed [revision] of [key] or a later one. Publishing never waits for it.
     */
    suspend fun propagation(key: SnapshotKey, revision: Long): Propagation {
        val recent = reports.statuses()
        return Propagation(revision, recent.count { (it.documents[key]?.revision ?: 0) >= revision }, recent.size)
    }
}

/** Every instance that has reported on a window so far, and its [summary] once it may close. */
class ShadowLook(val reporters: Set<String>, val summary: ShadowSummary?)

/** How many of the [recent] instances report [revision] of a document installed. */
data class Propagation(val revision: Long, val installed: Int, val recent: Int)
