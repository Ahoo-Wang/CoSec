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
    suspend fun close(state: ShadowState, now: Instant): ShadowSummary? {
        val counts = ShadowOutcome.entries.associate { it.name to 0L }.toMutableMap()
        for (report in reports.reports(state.window)) {
            report.counts.forEach { (outcome, count) -> counts.merge(outcome.name, count, Long::plus) }
        }
        val effective = ShadowOutcome.entries.filter { it.effective }.sumOf { counts.getValue(it.name) }
        val elapsed = java.time.Duration.between(state.startedAt, now)
        return when {
            counts.getValue(ShadowOutcome.CANDIDATE_ERROR.name) > 0 -> ShadowSummary(
                counts,
                now,
                insufficient = false,
                blocked = true
            )
            elapsed >= options.minDuration && effective >= options.minSamples -> ShadowSummary(
                counts,
                now,
                insufficient = false,
                blocked = false
            )
            elapsed >= options.maxDuration -> ShadowSummary(counts, now, insufficient = true, blocked = false)
            else -> null
        }
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

/** How many of the [recent] instances report [revision] of a document installed. */
data class Propagation(val revision: Long, val installed: Int, val recent: Int)
