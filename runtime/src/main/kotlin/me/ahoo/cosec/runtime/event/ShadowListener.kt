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

package me.ahoo.cosec.runtime.event

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.shadow.ShadowDiffKind
import java.util.concurrent.ThreadLocalRandom

/** The candidate documents a shadow listener evaluates against (RFC 0006 §9.5.4, RFC 0007 PR-7). */
fun interface CandidateViews {
    /** The view [enforced] would be with the candidates applied; null when no candidate touches it. */
    fun candidate(enforced: SnapshotView): SnapshotView?
}

/** A request the candidates decide differently from the enforced documents. */
class ShadowDecisionDiff(
    val kind: ShadowDiffKind,
    val requestId: String,
    val tenant: String,
    val principal: String,
    val method: String,
    val path: String,
    val enforced: Decision,
    val candidate: Decision,
) {
    override fun toString(): String =
        "ShadowDecisionDiff($kind, request=$requestId, $method $path, enforced=$enforced, candidate=$candidate)"
}

/** Where shadow diffs go. Called on the shadow listener's consumer, never on a request's thread. */
fun interface ShadowDecisionSink {
    fun publish(diff: ShadowDecisionDiff)
}

/**
 * Evaluates a sample of the requests again, against [candidates], and publishes the decisions that differ (RFC 0006
 * §9.5.4). Always BEST_EFFORT: it never runs obligations, never triggers REQUIRED listeners, and never fetches an
 * attribute: a candidate statement reading one the request did not fetch finds it missing, and the diff is
 * [ShadowDiffKind.CANDIDATE_INDETERMINATE].
 *
 * Whether a request is sampled is decided before its event is made ([sample]); only then does the event carry the
 * [ReplayHandle] that keeps its view reachable.
 */
class ShadowListener(
    override val name: String,
    /** The share of the requests evaluated again, from 0 to 1. */
    private val sampleRate: Double,
    private val candidates: CandidateViews,
    private val sink: ShadowDecisionSink,
    private val engine: FlatEngine = CoSecFlatEngine,
    /** Whether to report a different deciding rule with the same result (RULE_CHANGED). */
    private val reportRuleChanges: Boolean = false,
) : DecisionListener {
    init {
        require(sampleRate in 0.0..1.0) { "The shadow sample rate is between 0 and 1, not $sampleRate." }
    }

    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    internal fun sample(): Boolean = sampleRate > 0 && (sampleRate >= 1 || ThreadLocalRandom.current().nextDouble() < sampleRate)

    override suspend fun onDecision(event: DecisionEvent) {
        val replay = event.replay ?: return
        val enforced = event.decision ?: return
        if (name !in replay.sampledBy) {
            return
        }
        val view = candidates.candidate(replay.view) ?: return
        val candidate = engine.evaluate(replay.request, view, TraceLevel.OFF).decision
        val kind = kind(enforced, candidate) ?: return
        sink.publish(
            ShadowDecisionDiff(
                kind,
                event.requestId,
                event.subject.tenant,
                event.subject.id,
                event.request.method,
                event.request.path,
                enforced,
                candidate,
            )
        )
    }

    private fun kind(enforced: Decision, candidate: Decision): ShadowDiffKind? = when {
        enforced == candidate -> null
        candidate is Decision.Indeterminate && enforced !is Decision.Indeterminate ->
            if (candidate.cause is Cause.AttributeUnavailable) {
                ShadowDiffKind.CANDIDATE_INDETERMINATE
            } else {
                ShadowDiffKind.CANDIDATE_ERROR
            }
        candidate is Decision.Permit && enforced !is Decision.Permit -> ShadowDiffKind.WIDENED
        enforced is Decision.Permit && candidate !is Decision.Permit -> ShadowDiffKind.NARROWED
        reportRuleChanges && enforced::class == candidate::class -> ShadowDiffKind.RULE_CHANGED
        else -> null
    }

    override fun toString(): String = "ShadowListener($name, sampleRate=$sampleRate)"
}
