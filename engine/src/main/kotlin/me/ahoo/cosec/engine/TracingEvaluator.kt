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
 * the rules it evaluates there as after the decision. Its [visit] evaluates exactly the entries the untraced one does,
 * in the same order, and gives the layer its result through the same [matched], [failed] and [unresolved]. [TraceLevel.FULL] then stops where the untraced evaluation stops;
 * [TraceLevel.EXHAUSTIVE] also evaluates the layers after the decision.
 */
internal class TracingEvaluator(request: AuthzRequest, private val level: TraceLevel) : Evaluator(request) {
    /** Set while visiting a group only to list its entries. */
    private var listing = false

    private val layerTraces = ArrayList<LayerTrace>(LayerKind.entries.size)
    private var afterDecisionLayer = false

    // The layer and group being recorded.
    private var groupTraces = arrayOfNulls<GroupTraceBuilder>(0)
    private var groupTrace: GroupTraceBuilder? = null
    private var position = 0
    private var firstDeny: StatementRef? = null
    private var firstAllow: StatementRef? = null

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

    @Suppress("TooGenericExceptionCaught")
    override fun visit(entry: RuleEntry, grantedVia: String?): Boolean {
        val trace = checkNotNull(groupTrace)
        val position = position++
        val evaluated = !listing && when (entry) {
            is Rule -> entry.effect == pass
            is UnresolvedGrant -> pass == Effect.DENY
        }
        if (!evaluated) {
            // The DENY pass visits every entry, so each one gets its slot in source order.
            trace.reserve(
                position
            ) { RuleTrace(ref(entry, grantedVia), (entry as? Rule)?.effect, RuleOutcome.NOT_EVALUATED) }
            return false
        }
        val afterDecision = afterDecisionLayer || result != null
        val ruleTrace = when (entry) {
            is UnresolvedGrant ->
                RuleTrace(unresolved(entry, grantedVia), null, RuleOutcome.UNRESOLVED, entry.error, afterDecision)

            is Rule -> {
                val match = try {
                    entry.match(request)
                } catch (error: Exception) {
                    trace.set(
                        position,
                        RuleTrace(
                            failed(entry, grantedVia, error),
                            entry.effect,
                            RuleOutcome.ERROR,
                            error,
                            afterDecision
                        )
                    )
                    return false
                }
                val ref = if (match == RuleMatch.MATCHED) matched(entry, grantedVia) else ref(entry, grantedVia)
                if (match == RuleMatch.MATCHED) {
                    noteMatched(ref)
                }
                RuleTrace(ref, entry.effect, match.toOutcome(), null, afterDecision)
            }
        }
        trace.set(position, ruleTrace)
        // Never stops: the rest of the layer is visited for the trace.
        return false
    }

    private fun noteMatched(ref: StatementRef) {
        if (pass == Effect.DENY) {
            if (firstDeny == null) firstDeny = ref
        } else if (firstAllow == null) {
            firstAllow = ref
        }
    }

    override fun endLayer(groups: List<RuleGroup>) {
        val traces = ArrayList<GroupTrace>(groups.size)
        for (i in groups.indices) {
            val builder = checkNotNull(groupTraces[i])
            if (builder.guard != GuardOutcome.MATCHED) {
                // The rules of a group whose guard missed or threw are listed, never evaluated.
                // Listed with this evaluator rather than another visitor, so forEachRule sees one visit method.
                groupId = groups[i].id
                groupTrace = builder
                position = 0
                listing = true
                groups[i].forEachRule(this)
                listing = false
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
