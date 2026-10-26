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

package me.ahoo.cosec.parity

import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.explain.DecisionRef
import me.ahoo.cosec.explain.ExplainLayer
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What a replay must cover to count as gate evidence (RFC 0002 §8.3, Q7). The defaults are the RFC's; weaker thresholds
 * are for trying the tool out, and a replay judged by them is never evidence ([meetsRfc]). The report records the
 * thresholds it was judged by.
 *
 * @param minSamples the fewest samples
 * @param minDays the shortest span between the earliest and the latest sample
 * @param minDistinctDays the fewest distinct UTC days with samples: a long span with traffic on two days is not a week
 * @param maxBothFailedRate the largest share of samples both sides may fail alike: a replay where most samples fail
 * (missing policies, an unreadable export) agrees without deciding anything
 * @param minRecordedAgreementRate the smallest share of compared samples whose replayed decision agrees with the
 * recorded one: an export missing the policies or grants that decided the traffic agrees with itself, implicitly
 * denying everything, but not with the recording
 */
data class ReplayGate(
    val minSamples: Long = 1_000_000,
    val minDays: Double = 7.0,
    val minDistinctDays: Int = 7,
    val maxBothFailedRate: Double = 0.01,
    val minRecordedAgreementRate: Double = 0.9
) {
    init {
        require(minSamples >= 1) { "minSamples must be at least 1, not $minSamples." }
        require(minDays.isFinite() && minDays >= 0) { "minDays must be a finite, non-negative number, not $minDays." }
        require(minDistinctDays >= 0) { "minDistinctDays must not be negative, not $minDistinctDays." }
        require(maxBothFailedRate in 0.0..1.0) { "maxBothFailedRate must be within [0, 1], not $maxBothFailedRate." }
        require(minRecordedAgreementRate in 0.0..1.0) {
            "minRecordedAgreementRate must be within [0, 1], not $minRecordedAgreementRate."
        }
    }

    /** Whether every threshold is at least as strict as RFC 0002 §8.3's: only then is a met gate evidence. */
    fun meetsRfc(): Boolean {
        val rfc = ReplayGate()
        return minSamples >= rfc.minSamples &&
            minDays >= rfc.minDays &&
            minDistinctDays >= rfc.minDistinctDays &&
            maxBothFailedRate <= rfc.maxBothFailedRate &&
            minRecordedAgreementRate >= rfc.minRecordedAgreementRate
    }

    /** Why a replay with this [coverage] does not meet this gate; empty when it does. Differences are counted apart. */
    internal fun unmet(coverage: ReplayCoverage): List<String> = buildList {
        val samples = coverage.samples
        if (samples == 0L) {
            add("no samples")
        }
        if (samples < minSamples) {
            add("$samples samples, fewer than $minSamples")
        }
        if (coverage.spanDays < minDays) {
            add("a span of ${"%.2f".format(coverage.spanDays)} days, shorter than $minDays")
        }
        if (coverage.distinctDays < minDistinctDays) {
            add("samples on ${coverage.distinctDays} distinct days, fewer than $minDistinctDays")
        }
        if (samples > 0 && coverage.bothFailed.toDouble() / samples > maxBothFailedRate) {
            add(
                "${coverage.bothFailed} of $samples samples failed on both sides, more than ${maxBothFailedRate * 100} %"
            )
        }
        if (coverage.recordedAgreementRate < minRecordedAgreementRate) {
            add(
                "${coverage.recordedAgreement} of ${coverage.recordedCompared} compared samples agree with the recorded " +
                    "decision, fewer than ${minRecordedAgreementRate * 100} %",
            )
        }
        if (coverage.unexportedAppRequests > 0) {
            add(
                "${coverage.unexportedAppRequests} samples name apps ${coverage.unexportedApps} that " +
                    "${DecisionReplay.APP_ROLE_PERMISSIONS_FILE} does not export",
            )
        }
    }
}

/** What the gate judges, apart from the differences. */
internal class ReplayCoverage(
    val samples: Long,
    val spanDays: Double,
    val distinctDays: Int,
    val bothFailed: Long,
    val recordedAgreement: Long,
    val recordedCompared: Long,
    val unexportedAppRequests: Long,
    val unexportedApps: Set<String>
) {
    val recordedAgreementRate: Double
        get() = if (recordedCompared == 0L) 0.0 else recordedAgreement / recordedCompared.toDouble()
}

/**
 * The outcome of a replay, written to `replay-report.json`.
 *
 * @param from the earliest recorded timestamp, null without samples
 * @param to the latest recorded timestamp, null without samples
 * @param distinctDays the distinct UTC days with samples
 * @param duplicateEventIds samples whose `eventId` an earlier sample already had: the export may repeat events
 * @param bothFailed samples where both sides failed with the same exception class (counted in [same] too)
 * @param failedAlikeForDifferentCauses of [bothFailed], those whose causes differ (e.g. other missing policy ids):
 * though the §8.3 tuple compares the exception class only, they count as [differences]
 * @param byStatus samples per status the baseline answered
 * @param byLayer samples per layer of the baseline's deciding rule: `NONE` for an implicit deny, `ERROR` for a failure
 * @param baselineErrors failures of the baseline per exception class
 * @param candidateErrors failures of the candidate per exception class
 * @param recordedAgreement samples whose baseline decision agrees with the recorded one, out of [recordedCompared];
 * token failures are not compared (the replay has no token), a rate-limited request counts as allowed
 * @param unexportedAppRequests samples naming an app that `app-role-permissions.json` has no entry for, the apps in
 * [unexportedApps]: their role layer cannot be replayed
 * @param authenticatedInconsistent samples whose recorded `principal.authenticated` disagrees with the one derived from
 * the principal id: the export may be inconsistent
 * @param unrecordedInputs policies (`policy:<id>`) and app permissions (`app:<id>`) mapped to the unrecorded inputs
 * their conditions read: `principal.attributes`, `request.headers`, `request.attributes`, `script` for SpEL/OGNL
 * conditions and `#{...}` templates, or `custom:<type>` for a condition type `cosec-core` does not define
 * @param supplemented how many samples carried each optional input
 * @param inputs the SHA-256 of each input file, of the bytes the replay read
 * @param gate the thresholds the replay was judged by
 * @param gateUnmet why the replay does not meet [gate]; empty when it does
 * @param evidence whether the replay is gate evidence: no differences, the gate met, and thresholds at least as strict
 * as RFC 0002 §8.3's
 */
data class ReplayReport(
    val samples: Long,
    val from: Instant?,
    val to: Instant?,
    val spanDays: Double,
    val distinctDays: Int,
    val duplicateEventIds: Long,
    val same: Long,
    val mismatch: Long,
    val candidateError: Long,
    val bothFailed: Long,
    val failedAlikeForDifferentCauses: Long,
    val byStatus: Map<String, Long>,
    val byLayer: Map<String, Long>,
    val baselineErrors: Map<String, Long>,
    val candidateErrors: Map<String, Long>,
    val recordedAgreement: Long,
    val recordedCompared: Long,
    val recordedAgreementRate: Double,
    val unexportedAppRequests: Long,
    val unexportedApps: Set<String>,
    val authenticatedInconsistent: Long,
    val unrecordedInputs: Map<String, Set<String>>,
    val supplemented: Map<String, Long>,
    val inputs: Map<String, String>,
    val gate: ReplayGate,
    val gateUnmet: List<String>,
    val evidence: Boolean
) {
    /** Mismatches, candidate errors, and failures alike for different causes. */
    val differences: Long
        get() = mismatch + candidateError + failedAlikeForDifferentCauses

    fun summary(): String = buildString {
        appendLine(
            "Replayed $samples samples from $from to $to (${"%.2f".format(spanDays)} days, $distinctDays distinct days, " +
                "$duplicateEventIds duplicate event ids).",
        )
        appendLine(
            "same=$same mismatch=$mismatch candidate_error=$candidateError " +
                "failed_alike_for_different_causes=$failedAlikeForDifferentCauses",
        )
        appendLine(
            "Both failed alike: $bothFailed; baseline errors $baselineErrors; candidate errors $candidateErrors",
        )
        appendLine("By status: $byStatus; by layer: $byLayer")
        appendLine(
            "Agreement with recorded decisions: $recordedAgreement / $recordedCompared " +
                "(${"%.4f".format(recordedAgreementRate)}).",
        )
        if (unexportedAppRequests > 0) {
            appendLine("Samples naming apps the export lacks: $unexportedAppRequests, apps $unexportedApps")
        }
        if (authenticatedInconsistent > 0) {
            appendLine(
                "Samples whose recorded authenticated flag disagrees with the principal id: $authenticatedInconsistent"
            )
        }
        if (unrecordedInputs.isNotEmpty()) {
            appendLine("Read unrecorded inputs: $unrecordedInputs")
        }
        inputs.forEach { (file, hash) -> appendLine("sha256 $file $hash") }
        if (differences > 0) {
            appendLine(
                "DIFFERENCES: $mismatch mismatches, $candidateError candidate errors, " +
                    "$failedAlikeForDifferentCauses failures alike for different causes",
            )
        }
        when {
            gateUnmet.isNotEmpty() -> appendLine("GATE NOT MET ($gate): ${gateUnmet.joinToString("; ")}")
            !gate.meetsRfc() -> appendLine("Gate met (lenient thresholds — not gate evidence): $gate")
            else -> appendLine("Gate met: $gate")
        }
    }
}

internal class ReplayCounts(private val exportedApps: Set<String>) {
    private var samples = 0L
    private var from: Instant? = null
    private var to: Instant? = null
    private val days = HashSet<LocalDate>()
    private val eventIds = HashSet<String>()
    private var duplicateEventIds = 0L
    private val verdicts = LongArray(ParityVerdict.entries.size)
    private var bothFailed = 0L
    private var failedAlikeForDifferentCauses = 0L
    private val byStatus = sortedMapOf<String, Long>()
    private val byLayer = sortedMapOf<String, Long>()
    private val baselineErrors = sortedMapOf<String, Long>()
    private val candidateErrors = sortedMapOf<String, Long>()
    private var agreed = 0L
    private var compared = 0L
    private var unexportedAppRequests = 0L
    private val unexportedApps = sortedSetOf<String>()
    private var authenticatedInconsistent = 0L
    private val supplemented = sortedMapOf<String, Long>()

    fun add(event: ReplayEvent, result: ParityResult) {
        samples++
        from = from?.let { minOf(it, event.timestamp) } ?: event.timestamp
        to = to?.let { maxOf(it, event.timestamp) } ?: event.timestamp
        days += LocalDate.ofInstant(event.timestamp, ZoneOffset.UTC)
        event.eventId?.let {
            if (!eventIds.add(it)) {
                duplicateEventIds++
            }
        }
        val appId = event.request.appId
        if (appId.isNotEmpty() && appId !in exportedApps) {
            unexportedAppRequests++
            unexportedApps += appId
        }
        addOutcome(result)
        if (!event.authenticatedConsistent) {
            authenticatedInconsistent++
        }
        event.supplemented.forEach { supplemented.increment(it) }
        event.recorded.agrees(result.baseline)?.let { agrees ->
            compared++
            if (agrees) {
                agreed++
            }
        }
    }

    private fun addOutcome(result: ParityResult) {
        verdicts[result.verdict.ordinal]++
        if (result.bothFailed) {
            bothFailed++
        }
        if (result.failedAlikeForDifferentCauses) {
            failedAlikeForDifferentCauses++
        }
        val baseline = result.baseline
        byStatus.increment(baseline.tuple.status.name)
        byLayer.increment(if (baseline.failure != null) "ERROR" else baseline.tuple.decidedBy?.layer?.name ?: "NONE")
        baseline.tuple.error?.let { baselineErrors.increment(it) }
        result.candidate.tuple.error?.let { candidateErrors.increment(it) }
    }

    fun toReport(
        unrecordedInputs: Map<String, Set<String>>,
        inputs: Map<String, String>,
        gate: ReplayGate
    ): ReplayReport {
        val span = if (from != null && to != null) Duration.between(from, to) else Duration.ZERO
        val coverage = ReplayCoverage(
            samples = samples,
            spanDays = span.toMillis() / Duration.ofDays(1).toMillis().toDouble(),
            distinctDays = days.size,
            bothFailed = bothFailed,
            recordedAgreement = agreed,
            recordedCompared = compared,
            unexportedAppRequests = unexportedAppRequests,
            unexportedApps = unexportedApps,
        )
        val gateUnmet = gate.unmet(coverage)
        val mismatch = verdicts[ParityVerdict.MISMATCH.ordinal]
        val candidateError = verdicts[ParityVerdict.CANDIDATE_ERROR.ordinal]
        return ReplayReport(
            samples = samples,
            from = from,
            to = to,
            spanDays = coverage.spanDays,
            distinctDays = coverage.distinctDays,
            duplicateEventIds = duplicateEventIds,
            same = verdicts[ParityVerdict.SAME.ordinal],
            mismatch = mismatch,
            candidateError = candidateError,
            bothFailed = bothFailed,
            failedAlikeForDifferentCauses = failedAlikeForDifferentCauses,
            byStatus = byStatus,
            byLayer = byLayer,
            baselineErrors = baselineErrors,
            candidateErrors = candidateErrors,
            recordedAgreement = agreed,
            recordedCompared = compared,
            recordedAgreementRate = coverage.recordedAgreementRate,
            unexportedAppRequests = unexportedAppRequests,
            unexportedApps = unexportedApps,
            authenticatedInconsistent = authenticatedInconsistent,
            unrecordedInputs = unrecordedInputs,
            supplemented = supplemented,
            inputs = inputs,
            gate = gate,
            gateUnmet = gateUnmet,
            evidence = mismatch + candidateError + failedAlikeForDifferentCauses == 0L &&
                gateUnmet.isEmpty() && gate.meetsRfc(),
        )
    }

    private fun MutableMap<String, Long>.increment(key: String) {
        merge(key, 1L, Long::plus)
    }
}

/**
 * What the audit event recorded as the decision.
 */
internal class RecordedDecision(
    val decision: AuditDecision,
    val reasonCode: AuditReasonCode,
    val source: AuditSourceType,
    val policyId: String?,
    val statementIndex: Int?,
    val roleId: String?,
    val permissionId: String?
) {
    /** Null when the replay cannot reproduce the recorded decision: it has no token to reject. */
    fun agrees(side: ParitySide): Boolean? {
        if (reasonCode == AuditReasonCode.TOKEN_EXPIRED || reasonCode == AuditReasonCode.TOKEN_INVALID) {
            return null
        }
        // Rate limiting only ever applies to allowed requests and is not replayed.
        val recorded = if (decision == AuditDecision.TOO_MANY_REQUESTS) AuditDecision.ALLOW else decision
        return recorded == replayedDecision(side) && sameSource(side.tuple.decidedBy)
    }

    private fun replayedDecision(side: ParitySide): AuditDecision = when {
        side.failure != null && side.failure !is RegexTimeoutException -> AuditDecision.ERROR
        side.tuple.status == SecurityStatus.ALLOWED -> AuditDecision.ALLOW
        side.tuple.reason == AuthorizeResult.IMPLICIT_DENY.reason -> AuditDecision.IMPLICIT_DENY
        else -> AuditDecision.EXPLICIT_DENY
    }

    private fun sameSource(decidedBy: DecisionRef?): Boolean {
        return when (source) {
            AuditSourceType.GLOBAL_POLICY, AuditSourceType.PRINCIPAL_POLICY ->
                decidedBy?.policyId == policyId && decidedBy?.statementIndex == statementIndex

            AuditSourceType.ROLE_PERMISSION -> decidedBy?.roleId == roleId && decidedBy?.permissionId == permissionId
            AuditSourceType.ROOT -> decidedBy?.layer == ExplainLayer.ROOT
            AuditSourceType.BLACKLIST -> decidedBy?.layer == ExplainLayer.BLACKLIST
            AuditSourceType.NONE, AuditSourceType.UNKNOWN -> true
        }
    }
}
