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

import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef

/**
 * An [Evaluator] that records the trace (RFC 0002 §5.1).
 *
 * Within a layer that has a result it keeps going: it runs the remaining guards, visits the remaining rules and marks
 * the rules it evaluates there as after the decision. [TraceLevel.FULL] then stops where the untraced evaluation stops;
 * [TraceLevel.EXHAUSTIVE] also evaluates the layers after the decision.
 */
internal class TracingEvaluator(request: AuthzRequest, private val level: TraceLevel) : Evaluator(request) {
    private val layerTraces = ArrayList<LayerTrace>(LayerKind.entries.size)
    private var afterDecisionLayer = false

    // The layer and group being recorded.
    private var groupTraces = arrayOfNulls<GroupTraceBuilder>(0)
    private var groupTrace: GroupTraceBuilder? = null
    private var position = 0
    private var firstDeny: StatementRef? = null
    private var firstAllow: StatementRef? = null

    override fun stopsAtResult(): Boolean = false

    override fun finish(decision: Decision, layers: List<LayerInput>, next: Int): Evaluation {
        afterDecisionLayer = true
        for (i in next until layers.size) {
            if (level == TraceLevel.EXHAUSTIVE) {
                evaluateLayer(layers[i])
            } else {
                layerTraces.add(LayerTrace(layers[i].kind, LayerState.NOT_EVALUATED))
            }
        }
        return Evaluation(decision, Trace(request.environment.blocked, request.subject.root, layerTraces))
    }

    override fun layerWithoutGroups(kind: LayerKind, state: LayerState, reason: AbsentReason?, cause: Cause?) {
        layerTraces.add(LayerTrace(kind, state, absentReason = reason, cause = cause))
    }

    override fun beginLayer(groups: Int) {
        groupTraces = arrayOfNulls(groups)
        firstDeny = null
        firstAllow = null
    }

    override fun guardEvaluated(index: Int, group: RuleGroup, outcome: GuardOutcome, error: Throwable?) {
        groupTraces[index] = GroupTraceBuilder(group.id, outcome, error)
    }

    override fun beginGroup(index: Int) {
        groupTrace = groupTraces[index]
        position = 0
    }

    override fun ruleSkipped(entry: RuleEntry, grantedVia: String?) {
        // The DENY pass visits every entry, so each one gets its slot in source order.
        val trace = checkNotNull(groupTrace)
        trace.reserve(
            position++
        ) { RuleTrace(ref(entry, grantedVia), (entry as? Rule)?.effect, RuleOutcome.NOT_EVALUATED) }
    }

    override fun ruleEvaluated(rule: Rule, grantedVia: String?, match: RuleMatch, afterResult: Boolean) {
        val ref = ref(rule, grantedVia)
        record(RuleTrace(ref, rule.effect, match.toOutcome(), null, afterDecisionLayer || afterResult))
        if (match == RuleMatch.MATCHED) {
            if (rule.effect == Effect.DENY) {
                if (firstDeny == null) firstDeny = ref
            } else if (firstAllow == null) {
                firstAllow = ref
            }
        }
    }

    override fun ruleFailed(ref: StatementRef, effect: Effect, error: Throwable, afterResult: Boolean) {
        record(RuleTrace(ref, effect, RuleOutcome.ERROR, error, afterDecisionLayer || afterResult))
    }

    override fun grantUnresolved(ref: StatementRef, error: Throwable, afterResult: Boolean) {
        record(RuleTrace(ref, null, RuleOutcome.UNRESOLVED, error, afterDecisionLayer || afterResult))
    }

    private fun record(trace: RuleTrace) {
        checkNotNull(groupTrace).set(position++, trace)
    }

    override fun endLayer(groups: List<RuleGroup>) {
        val traces = ArrayList<GroupTrace>(groups.size)
        for (i in groups.indices) {
            val builder = checkNotNull(groupTraces[i])
            if (builder.guard != GuardOutcome.MATCHED) {
                // The rules of a group whose guard missed or threw are listed, never evaluated.
                groupId = groups[i].id
                groupTrace = builder
                position = 0
                groups[i].forEachRule(notEvaluated)
            }
            traces.add(builder.build())
        }
        val matched = firstDeny ?: firstAllow
        layerTraces.add(
            LayerTrace(
                kind = kind,
                state = if (afterDecisionLayer) LayerState.EVALUATED_AFTER_DECISION else LayerState.EVALUATED,
                matched = matched,
                matchedEffect = when {
                    firstDeny != null -> Effect.DENY
                    firstAllow != null -> Effect.ALLOW
                    else -> null
                },
                cause = (result as? Decision.Indeterminate)?.cause,
                groups = traces,
            ),
        )
    }

    private val notEvaluated = RuleVisitor { entry, grantedVia ->
        ruleSkipped(entry, grantedVia)
        false
    }

    private class GroupTraceBuilder(val id: String, val guard: GuardOutcome, val guardError: Throwable?) {
        private val rules = ArrayList<RuleTrace>()

        fun reserve(position: Int, trace: () -> RuleTrace) {
            if (position >= rules.size) {
                rules.add(trace())
            }
        }

        fun set(position: Int, trace: RuleTrace) {
            if (position < rules.size) {
                rules[position] = trace
            } else {
                rules.add(trace)
            }
        }

        fun build(): GroupTrace = GroupTrace(id, guard, guardError, rules)
    }

    private companion object {
        fun RuleMatch.toOutcome(): RuleOutcome =
            when (this) {
                RuleMatch.MATCHED -> RuleOutcome.MATCHED
                RuleMatch.ACTION_MISS -> RuleOutcome.ACTION_MISS
                RuleMatch.CONDITION_MISS -> RuleOutcome.CONDITION_MISS
            }
    }
}
