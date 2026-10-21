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
 * in the same order, and gives the layer its result through the same [matched], [failed] and [unresolved].
 * [TraceLevel.FULL] then stops where the untraced evaluation stops; [TraceLevel.EXHAUSTIVE] also evaluates the layers
 * after the decision.
 *
 * The decision never depends on the trace level (property P2). So whatever it does only for the trace (listing the
 * entries of a group whose guard did not match, and everything after the decision) records failures instead of
 * throwing: a broken entry, a throwing `forEachRule` or matcher, a null match. Only the JVM's own errors and an
 * interruption propagate. Before the decision it fails exactly where the untraced evaluation does.
 */
internal class TracingEvaluator(request: AuthzRequest, private val level: TraceLevel) : Evaluator(request) {
    /** Set while visiting a group only to list its entries. */
    private var listing = false

    /** The entries `forEachRule` visited in the current pass of the current group, with their granting roles. */
    private val visited = ArrayList<RuleEntry>()
    private val visitedVia = ArrayList<String?>()

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
        visited.clear()
        visitedVia.clear()
    }

    override fun endGroup() {
        recordVisited()
        // The allow pass must visit exactly what the deny pass did. Only the trace remembers the deny pass, so a
        // mismatch is recorded rather than thrown: the untraced evaluation cannot see it and decides regardless.
        val trace = checkNotNull(groupTrace)
        if (pass == Effect.ALLOW && position != trace.entryCount) {
            trace.breach(misaligned(trace.entryCount, position))
        }
    }

    override fun evaluatingForTraceOnly(): Boolean = listing || afterDecisionLayer || result != null

    /**
     * Only collects the pass's entries; [endGroup] evaluates them, in the same order, as soon as `forEachRule` returns.
     *
     * Why not evaluate here: a group's `forEachRule` is compiled once for both evaluators, and whatever this visit
     * inlines into it makes it too big for the JIT to inline into the untraced evaluator, which then runs at half its
     * speed for as long as any decision is ever traced (EngineBenchmark `off-mixed`, round 2). A visit that only adds to
     * a list cannot grow it.
     */
    override fun visit(entry: RuleEntry, grantedVia: String?): Boolean {
        visited.add(entry)
        visitedVia.add(grantedVia)
        // Never stops: the rest of the layer is visited for the trace.
        return false
    }

    private fun recordVisited() {
        for (i in visited.indices) {
            record(visited[i], visitedVia[i])
        }
        visited.clear()
        visitedVia.clear()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun record(entry: RuleEntry, grantedVia: String?) {
        val trace = checkNotNull(groupTrace)
        val position = position++
        if (!evaluatingForTraceOnly()) {
            step(trace, position, entry, grantedVia, afterDecision = false)
        } else {
            try {
                step(trace, position, entry, grantedVia, afterDecision = true)
            } catch (error: Throwable) {
                if (!recordable(error)) {
                    throw error
                }
                trace.breach(error)
            }
        }
    }

    /** One entry, checked and evaluated as the untraced evaluator would, then recorded at [position]. */
    private fun step(
        trace: GroupTraceBuilder,
        position: Int,
        entry: RuleEntry,
        grantedVia: String?,
        afterDecision: Boolean
    ) {
        checkEntryAndEffect(entry, grantedVia)
        if (!listing && !trace.align(position, entry, pass)) {
            trace.breach(misaligned(trace.entryCount, position + 1))
        }
        val evaluated = !listing && when (entry) {
            is Rule -> entry.effect == pass
            is UnresolvedGrant -> pass == Effect.DENY
        }
        if (!evaluated) {
            // The DENY pass visits every entry, so each one gets its slot in source order.
            trace.reserve(
                position
            ) { RuleTrace(ref(entry, grantedVia), (entry as? Rule)?.effect, RuleOutcome.NOT_EVALUATED) }
            return
        }
        trace.set(
            position,
            when (entry) {
                is UnresolvedGrant ->
                    RuleTrace(unresolved(entry, grantedVia), null, RuleOutcome.UNRESOLVED, entry.error, afterDecision)

                is Rule -> evaluate(entry, grantedVia, afterDecision)
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun evaluate(rule: Rule, grantedVia: String?, afterDecision: Boolean): RuleTrace {
        val match: RuleMatch? = try {
            rule.match(request)
        } catch (error: InterruptedException) {
            // Fails the layer as untraced; after the decision it propagates rather than being recorded.
            if (afterDecision) {
                throw error
            }
            return failure(rule, grantedVia, error, afterDecision)
        } catch (error: Exception) {
            // As untraced, an Exception fails the layer; after the decision it is only recorded.
            return failure(rule, grantedVia, error, afterDecision)
        } catch (error: Throwable) {
            // Propagates as untraced, unless it comes from a rule evaluated only for the trace.
            if (!(afterDecision && recordable(error))) {
                throw error
            }
            return failure(rule, grantedVia, error, afterDecision)
        }
        val outcome = when (checkMatch(match)) {
            RuleMatch.MATCHED -> RuleOutcome.MATCHED
            RuleMatch.ACTION_MISS -> RuleOutcome.ACTION_MISS
            RuleMatch.CONDITION_MISS -> RuleOutcome.CONDITION_MISS
        }
        val ref = if (outcome == RuleOutcome.MATCHED) matched(rule, grantedVia) else ref(rule, grantedVia)
        if (outcome == RuleOutcome.MATCHED) {
            noteMatched(ref)
        }
        return RuleTrace(ref, rule.effect, outcome, null, afterDecision)
    }

    private fun failure(rule: Rule, grantedVia: String?, error: Throwable, afterDecision: Boolean): RuleTrace =
        RuleTrace(failed(rule, grantedVia, error), rule.effect, RuleOutcome.ERROR, error, afterDecision)

    private fun misaligned(denyPass: Int, allowPass: Int) = IllegalStateException(
        "Group $groupId visited $allowPass entries in its allow pass, not the $denyPass of its deny pass, or not in the same order.",
    )

    override fun recordsAfterDecision(error: Throwable): Boolean =
        (afterDecisionLayer || result != null) && recordable(error)

    /**
     * Whether a failure of something evaluated only for the trace is recorded instead of failing the evaluation: the
     * untraced evaluation never calls it, so explaining a decision must not fail where enforcing it does not. The JVM's
     * own failures propagate, and so does an interruption: the engine may not touch the thread's interrupt flag.
     */
    private fun recordable(error: Throwable): Boolean =
        error !is VirtualMachineError && error !is LinkageError && error !is InterruptedException

    private fun noteMatched(ref: StatementRef) {
        if (pass == Effect.DENY) {
            if (firstDeny == null) firstDeny = ref
        } else if (firstAllow == null) {
            firstAllow = ref
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun endLayer(groups: List<RuleGroup>) {
        val traces = ArrayList<GroupTrace>(groups.size)
        for (i in groups.indices) {
            val builder = checkNotNull(groupTraces[i])
            if (builder.guard != GuardOutcome.MATCHED) {
                // The rules of a group whose guard missed or threw are listed, never evaluated, and only for the trace.
                // Listed with this evaluator rather than another visitor, so forEachRule sees one visit method.
                groupId = groups[i].id
                groupTrace = builder
                position = 0
                visited.clear()
                visitedVia.clear()
                listing = true
                try {
                    if (groups[i].forEachRule(this)) {
                        builder.breach(
                            IllegalStateException("Group $groupId returned true from forEachRule while listed")
                        )
                    }
                } catch (error: Throwable) {
                    if (!recordable(error)) {
                        throw error
                    }
                    builder.breach(error)
                }
                // Lists what was visited, even when forEachRule threw part-way.
                try {
                    recordVisited()
                } finally {
                    listing = false
                }
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
        /** By position; a slot stays empty where an entry could not be recorded. */
        private val rules = ArrayList<RuleTrace?>()

        /** The entries the deny pass visited, by position. */
        private val entries = ArrayList<RuleEntry>()
        private var contractError: Throwable? = null

        val entryCount: Int get() = entries.size

        /** Remembers the deny pass's entries; in the allow pass, whether [entry] is the one the deny pass saw here. */
        fun align(position: Int, entry: RuleEntry, pass: Effect): Boolean {
            if (pass == Effect.DENY) {
                entries.add(entry)
                return true
            }
            return position < entries.size && entries[position] === entry
        }

        /** The group broke the adapter contract while traced; the first breach is kept. */
        fun breach(error: Throwable) {
            if (contractError == null) {
                contractError = error
            }
        }

        fun reserve(position: Int, trace: () -> RuleTrace) {
            while (rules.size <= position) {
                rules.add(null)
            }
            if (rules[position] == null) {
                rules[position] = trace()
            }
        }

        fun set(position: Int, trace: RuleTrace) {
            while (rules.size <= position) {
                rules.add(null)
            }
            rules[position] = trace
        }

        fun build(): GroupTrace = GroupTrace(id, guard, guardError, rules.filterNotNull(), contractError)
    }
}
