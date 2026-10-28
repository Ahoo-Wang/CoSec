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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** How a sampled request came out on the candidate, against the enforced documents (RFC 0007 §6.4). */
enum class ShadowOutcome {
    /** The same decision, by the same statement. */
    SAME,

    /** The enforced documents did not permit; the candidate does. */
    WIDENED,

    /** The enforced documents permitted; the candidate does not. */
    NARROWED,

    /** The candidate is indeterminate where the enforced documents were not, for a reason other than a missing attribute. */
    BECAME_INDETERMINATE,

    /** The candidate reads an external attribute the request did not fetch (RFC 0006 §9.5.4): a shadow never fetches. */
    CANDIDATE_INDETERMINATE,

    /** The candidate cannot be compiled or evaluated on this instance; it must not be published (RFC 0007 §6.3). */
    CANDIDATE_ERROR,

    /** The same result, decided by another statement. */
    BY_CHANGED,

    /** The candidate's base is not what this instance has installed: not compared. */
    BASE_MISMATCH,
    ;

    /** Whether this outcome is a sample of the candidate; [BASE_MISMATCH] compares nothing. */
    val effective: Boolean get() = this != BASE_MISMATCH
}

/**
 * One sampled request (RFC 0007 §6.4): the fields a decision event has (RFC 0006 §9.2), and the statements that decided
 * on each side, never a header, a token or an attribute value.
 */
data class ShadowSample(
    val tenant: String,
    val principal: String,
    val app: String,
    val space: String,
    val action: String,
    val resource: String,
    val enforcedBy: String?,
    val candidateBy: String?,
)

/**
 * What one instance saw of one shadow window (RFC 0007 §6.4): counts by outcome, at most [MAX_SAMPLES] samples of each,
 * the base revisions it compared against, and the first and last sample times. Cumulative: each write replaces the
 * instance's previous report for the window.
 */
data class ShadowReport(
    val window: String,
    val instance: String,
    val counts: Map<ShadowOutcome, Long>,
    val samples: Map<ShadowOutcome, List<ShadowSample>>,
    val baseRevisions: Set<Long>,
    val first: Instant?,
    val last: Instant?,
) {
    companion object {
        const val MAX_SAMPLES: Int = 20

        private val INSTANCE_ID = Regex("[A-Za-z0-9._-]+")

        /**
         * Whether [id] may name an instance: letters, digits, `.`, `_` and `-` only (round-2 review). An instance id
         * ends its report's Redis key, so a `:` in it would let one instance's key, or an ACL pattern written for it,
         * match another's.
         */
        fun isInstanceId(id: String): Boolean = INSTANCE_ID.matches(id)
    }
}

/** A document an instance has installed (RFC 0007 §6.5): its revision, and how long since it was last confirmed. */
data class InstalledDocument(val revision: Long, val lag: Duration)

/** What an instance reported having installed, at [at] (RFC 0007 §6.5). */
data class InstanceStatus(val instance: String, val at: Instant, val documents: Map<SnapshotKey, InstalledDocument>)

/** Where each instance writes its shadow reports and its status (RFC 0007 §6.4, §6.5). */
interface ShadowReportSink {
    suspend fun write(report: ShadowReport)

    suspend fun write(status: InstanceStatus)
}

/** What the control plane reads back: every instance's report of a window, and the statuses written recently. */
interface ShadowReportReader {
    suspend fun reports(window: String): List<ShadowReport>

    /** Statuses written within [STATUS_TTL] of now. */
    suspend fun statuses(): List<InstanceStatus>

    companion object {
        /** An instance that has not written its status for this long is not counted (RFC 0007 §6.5). */
        val STATUS_TTL: Duration = Duration.ofMinutes(1)
    }
}

/** Reports and statuses in memory: for tests and single-instance deployments. */
class InMemoryShadowReports(private val clock: Clock = Clock.systemUTC()) : ShadowReportSink, ShadowReportReader {
    private val reports = ConcurrentHashMap<String, ConcurrentHashMap<String, ShadowReport>>()
    private val statuses = ConcurrentHashMap<String, InstanceStatus>()

    override suspend fun write(report: ShadowReport) {
        reports.computeIfAbsent(report.window) { ConcurrentHashMap() }[report.instance] = report
    }

    override suspend fun write(status: InstanceStatus) {
        statuses[status.instance] = status
    }

    override suspend fun reports(window: String): List<ShadowReport> = reports[window]?.values?.toList().orEmpty()

    override suspend fun statuses(): List<InstanceStatus> {
        val since = clock.instant().minus(ShadowReportReader.STATUS_TTL)
        return statuses.values.filter { !it.at.isBefore(since) }
    }
}
