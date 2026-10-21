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

package me.ahoo.cosec.engine

import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef

/** What one evaluation did, recorded at [TraceLevel.FULL] or [TraceLevel.EXHAUSTIVE]. */
class Trace(val blocked: Boolean, val root: Boolean, val layers: List<LayerTrace>)

class LayerTrace(
    val kind: LayerKind,
    val state: LayerState,
    val absentReason: AbsentReason? = null,
    /** The layer on its own: its first matched DENY, else its first matched ALLOW, else null. */
    val matched: StatementRef? = null,
    val matchedEffect: Effect? = null,
    /** Why the layer is indeterminate: the first error in evaluation order, or the layer's missing or unavailable data. */
    val cause: Cause? = null,
    val groups: List<GroupTrace> = emptyList(),
)

enum class LayerState {
    /** Reached by the decision. */
    EVALUATED,

    /** Evaluated at [TraceLevel.EXHAUSTIVE] only, after the decision was made in an earlier layer. */
    EVALUATED_AFTER_DECISION,

    /** Not reached by the decision, and not evaluated. */
    NOT_EVALUATED,

    /** [LayerInput.Absent]. */
    ABSENT,

    /** [LayerInput.Unavailable]. */
    UNAVAILABLE,

    /** [LayerInput.Loaded] with missing policies; see [LayerTrace.cause]. */
    MISSING_POLICIES,
}

/** [rules] are in source order, as [RuleGroup.forEachRule] visits them. */
class GroupTrace(val id: String, val guard: GuardOutcome, val guardError: Throwable?, val rules: List<RuleTrace>)

enum class GuardOutcome { MATCHED, MISSED, ERROR }

/** A rule, or an [UnresolvedGrant]: then [effect] is null and the outcome is [RuleOutcome.UNRESOLVED] or not evaluated. */
class RuleTrace(
    val ref: StatementRef,
    val effect: Effect?,
    val outcome: RuleOutcome,
    val error: Throwable? = null,
    /**
     * Evaluated only for the trace: after its layer had a result, or in a layer after the decision. Its outcome and
     * error never affect the decision.
     */
    val afterDecision: Boolean = false,
)

enum class RuleOutcome {
    MATCHED,
    ACTION_MISS,
    CONDITION_MISS,
    NOT_EVALUATED,

    /** The rule's matcher threw; see [RuleTrace.error]. */
    ERROR,

    /** The deny pass reached an [UnresolvedGrant]; [RuleTrace.error] is its exception. */
    UNRESOLVED,
}
