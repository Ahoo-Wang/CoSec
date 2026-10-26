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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.Scope

/**
 * What one evaluation over bindings did (RFC 0004 §6.5), recorded at [me.ahoo.cosec.engine.TraceLevel.FULL] or
 * [me.ahoo.cosec.engine.TraceLevel.EXHAUSTIVE]. It replaces the layered trace when the layers go (PR-8).
 *
 * [candidates] are in evaluation order: the DENY statements of every candidate in candidate order, then the ALLOW ones.
 * Both levels list every statement of every candidate. FULL stops evaluating where the untraced evaluation stops,
 * except that it finishes the pass that decided; the rest is [CandidateOutcome.NOT_EVALUATED]. EXHAUSTIVE evaluates the
 * ALLOW pass even when the DENY pass decided.
 */
class FlatTrace(
    val scope: Scope,
    val candidates: List<CandidateTrace>,
    /** Bindings that would apply to the request had they not expired, in candidate order. */
    val expired: List<BindingRef.Bound>,
    /** What the request could not resolve: the caller's, then documents not given, then missing policies. */
    val unresolved: List<Cause>,
)

/** One statement of a candidate. [ref] says through which binding or attachment it applied. */
class CandidateTrace(
    val ref: StatementRef.Policy,
    val effect: Effect,
    val outcome: CandidateOutcome,
    /** Set for [CandidateOutcome.ERROR] and [CandidateOutcome.GUARD_ERROR]. */
    val error: Throwable? = null,
    /**
     * Evaluated (or listed) only for the trace, after the decision was known. Its outcome and error never affect the
     * decision.
     */
    val afterDecision: Boolean = false,
) {
    val via: BindingRef get() = ref.via
}

enum class CandidateOutcome {
    MATCHED,
    ACTION_MISS,
    CONDITION_MISS,

    /** The policy condition did not match, so the statement was not evaluated. */
    GUARD_MISS,

    /** The policy condition threw; see [CandidateTrace.error]. A DENY counts as an error, an ALLOW as no match. */
    GUARD_ERROR,

    /** The statement threw, or returned no result; see [CandidateTrace.error]. */
    ERROR,

    NOT_EVALUATED,
}
