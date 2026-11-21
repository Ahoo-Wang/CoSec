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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.engine.trace.CandidateOutcome
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.scope.ScopeResolution

/**
 * The closed sets the control API writes into a decision (`simulate`, `explain`) and its trace, one table each
 * (R2C-11, `CTL-61`). The values are the API's, not the names of the model's classes and enums: a renamed `Cause`
 * class or engine enum changes nothing a client reads, and a new one does not compile until a table names its value.
 * `contract/control/vocabularies.json` pins them.
 */
internal object ControlVocabulary {
    const val PERMIT = "Permit"
    const val DENY = "Deny"
    const val INDETERMINATE = "Indeterminate"
    const val REJECTED = "Rejected"

    /** `decision`: what was decided, `Rejected` when the scope was refused before anything was decided. */
    val DECISIONS = listOf(PERMIT, DENY, INDETERMINATE, REJECTED)

    /** `cause`, on an `Indeterminate` decision only: what kind of failure left it undecided, never its message. */
    val CAUSES: List<String> = CauseValue.entries.map { it.value }

    /** `reason`, on a `Rejected` decision only: why the scope was refused. */
    val REASONS: List<String> = ScopeResolution.Rejected.Reason.entries.map(::reason)

    /** `effect` of a trace entry. */
    val EFFECTS: List<String> = Effect.entries.map(::effect)

    /** `outcome` of a trace entry. */
    val OUTCOMES: List<String> = CandidateOutcome.entries.map(::outcome)

    fun cause(cause: Cause): String = when (cause) {
        is Cause.MatcherFailed -> CauseValue.MATCHER_FAILED
        is Cause.MissingPolicies -> CauseValue.MISSING_POLICIES
        is Cause.AmbiguousPolicy -> CauseValue.AMBIGUOUS_POLICY
        is Cause.SourceUnavailable -> CauseValue.SOURCE_UNAVAILABLE
        is Cause.DocumentInvalid -> CauseValue.DOCUMENT_INVALID
        is Cause.NotLoaded -> CauseValue.NOT_LOADED
        is Cause.BoundaryUnavailable -> CauseValue.BOUNDARY_UNAVAILABLE
        is Cause.AttributeUnavailable -> CauseValue.ATTRIBUTE_UNAVAILABLE
        is Cause.CheckFailed -> CauseValue.CHECK_FAILED
        is Cause.MembershipUnavailable -> CauseValue.MEMBERSHIP_UNAVAILABLE
        is Cause.RequiredListenerFailed -> CauseValue.REQUIRED_LISTENER_FAILED
        is Cause.RateLimiterUnavailable -> CauseValue.RATE_LIMITER_UNAVAILABLE
    }.value

    fun reason(reason: ScopeResolution.Rejected.Reason): String = when (reason) {
        ScopeResolution.Rejected.Reason.APP_MISMATCH -> "APP_MISMATCH"
        ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER -> "SPACE_NOT_MEMBER"
    }

    fun effect(effect: Effect): String = when (effect) {
        Effect.ALLOW -> "ALLOW"
        Effect.DENY -> "DENY"
    }

    fun outcome(outcome: CandidateOutcome): String = when (outcome) {
        CandidateOutcome.MATCHED -> "MATCHED"
        CandidateOutcome.ACTION_MISS -> "ACTION_MISS"
        CandidateOutcome.CONDITION_MISS -> "CONDITION_MISS"
        CandidateOutcome.RESOURCE_MISS -> "RESOURCE_MISS"
        CandidateOutcome.GUARD_MISS -> "GUARD_MISS"
        CandidateOutcome.GUARD_ERROR -> "GUARD_ERROR"
        CandidateOutcome.ERROR -> "ERROR"
        CandidateOutcome.NOT_EVALUATED -> "NOT_EVALUATED"
    }

    /** The values of `cause`, the spellings 8.0's previews wrote. */
    private enum class CauseValue(val value: String) {
        MATCHER_FAILED("MatcherFailed"),
        MISSING_POLICIES("MissingPolicies"),
        AMBIGUOUS_POLICY("AmbiguousPolicy"),
        SOURCE_UNAVAILABLE("SourceUnavailable"),
        DOCUMENT_INVALID("DocumentInvalid"),
        NOT_LOADED("NotLoaded"),
        BOUNDARY_UNAVAILABLE("BoundaryUnavailable"),
        ATTRIBUTE_UNAVAILABLE("AttributeUnavailable"),
        CHECK_FAILED("CheckFailed"),
        MEMBERSHIP_UNAVAILABLE("MembershipUnavailable"),
        REQUIRED_LISTENER_FAILED("RequiredListenerFailed"),
        RATE_LIMITER_UNAVAILABLE("RateLimiterUnavailable"),
    }
}
