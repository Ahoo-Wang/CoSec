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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.trace.CandidateOutcome
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.runtime.event.CauseName

/**
 * The closed sets the control API writes into a decision (`simulate`, `explain`) and its trace, one table each
 * (R2C-11, `CTL-61`). The values are the API's, not the names of the model's classes and enums: a renamed `Cause`
 * class or engine enum changes nothing a client reads, and a new one does not compile until a table names its value.
 * A cause is named by runtime's [CauseName], the table the audit record and the shadow report write from too (`OBS-9`).
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
    val CAUSES: List<String> = CauseName.entries.map { it.wire }

    /** `reason`, on a `Rejected` decision only: why the scope was refused. */
    val REASONS: List<String> = ScopeResolution.Rejected.Reason.entries.map(::reason)

    /** `effect` of a trace entry. */
    val EFFECTS: List<String> = Effect.entries.map(::effect)

    /** `outcome` of a trace entry. */
    val OUTCOMES: List<String> = CandidateOutcome.entries.map(::outcome)

    fun cause(cause: Cause): String = CauseName.of(cause).wire

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
}
