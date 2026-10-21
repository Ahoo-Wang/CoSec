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
 * One evaluation: the algorithm of RFC 0002 §4.1, and its only implementation.
 *
 * Untraced, it stops at the first result and records nothing: besides itself, it allocates only the decision and, for
 * a layer with more than 64 groups, the guard bitmap. It is also the [RuleVisitor] of every group it evaluates, so
 * visiting rules allocates no visitor, and its fields are those of a trace-free evaluator (EngineBenchmark's control).
 *
 * [TracingEvaluator] runs the same layer, guard and pass loops and records through the hooks at the end, which the
 * untraced path never calls. It overrides only [visit], the step per rule, to keep going after the layer's result
 * instead of stopping. Both visits reach a result only through [matched], [failed] and [unresolved], which keep the
 * layer's first one, so the decision is the same at every [TraceLevel] (property P2).
 *
 * Why [visit] is not one method with checks for tracing: per rule, any trace code left in the untraced path, even
 * behind a check, kept the JIT from compiling it as tightly as the control and cost half to two thirds of the
 * throughput (EngineBenchmark, `allow` and `deny`).
 */
internal open class Evaluator(protected val request: AuthzRequest) : RuleVisitor {
    protected var kind: LayerKind = LayerKind.GLOBAL
    protected var groupId: String = ""

    /** The effect whose rules the current pass evaluates. */
    protected var pass: Effect = Effect.DENY
        private set

    /** The layer's result so far: its first error, else its first matched DENY, else its first matched ALLOW. */
    protected var result: Decision? = null

    private val tracing: Boolean get() = this is TracingEvaluator

    fun evaluate(layers: List<LayerInput>): Evaluation {
        if (request.environment.blocked) {
            return end(CoSecEngine.BLACKLIST, layers, 0)
        }
        if (request.subject.root) {
            return end(CoSecEngine.ROOT, layers, 0)
        }
        var permit: Decision? = null
        for (i in layers.indices) {
            when (val layerResult = evaluateLayer(layers[i])) {
                null -> Unit
                is Decision.Permit -> if (permit == null) permit = layerResult
                // A DENY or an error in any layer ends the evaluation, whatever earlier layers allowed (S6).
                else -> return end(layerResult, layers, i + 1)
            }
        }
        return end(permit ?: CoSecEngine.IMPLICIT_DENY, layers, layers.size)
    }

    private fun end(decision: Decision, layers: List<LayerInput>, next: Int): Evaluation =
        if (tracing) finish(decision, layers, next) else CoSecEngine.untraced(decision)

    /** Returns the layer's result, or null when nothing in it matched. */
    protected fun evaluateLayer(layer: LayerInput): Decision? =
        when (layer) {
            is LayerInput.Absent -> {
                if (tracing) layerWithoutGroups(layer.kind, LayerState.ABSENT, layer.reason, null)
                null
            }

            is LayerInput.Unavailable -> {
                val cause = Cause.SourceUnavailable(layer.kind, layer.error)
                if (tracing) layerWithoutGroups(layer.kind, LayerState.UNAVAILABLE, null, cause)
                Decision.Indeterminate(cause)
            }

            is LayerInput.Loaded -> {
                val missing = layer.missing
                if (missing != null) {
                    // Before any guard: a missing policy may be the deny that keeps the request out (S8).
                    if (tracing) layerWithoutGroups(layer.kind, LayerState.MISSING_POLICIES, null, missing)
                    Decision.Indeterminate(missing)
                } else {
                    evaluateGroups(layer)
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun evaluateGroups(layer: LayerInput.Loaded): Decision? {
        kind = layer.kind
        result = null
        val groups = layer.groups
        val size = groups.size
        val tracing = tracing
        if (tracing) beginLayer(size)
        // Every guard of the layer runs before any rule (S4), so which ones matched has to be kept.
        var matchedBits = 0L
        val matchedWords = if (size > Long.SIZE_BITS) LongArray(size / Long.SIZE_BITS + 1) else null
        for (i in 0 until size) {
            val group = groups[i]
            val matched = try {
                group.guard.matches(request)
            } catch (error: Exception) {
                if (result == null) {
                    result = Decision.Indeterminate(Cause.MatcherFailed(kind, group.id, null, error))
                }
                if (!tracing) {
                    return result
                }
                guardEvaluated(i, group, GuardOutcome.ERROR, error)
                continue
            }
            if (matched) {
                if (matchedWords == null) {
                    matchedBits = matchedBits or (1L shl i)
                } else {
                    val word = i ushr WORD_SHIFT
                    matchedWords[word] = matchedWords[word] or (1L shl i)
                }
            }
            if (tracing) guardEvaluated(i, group, if (matched) GuardOutcome.MATCHED else GuardOutcome.MISSED, null)
        }
        // Every DENY before any ALLOW (S5).
        if (!visitRules(groups, matchedBits, matchedWords, Effect.DENY)) {
            visitRules(groups, matchedBits, matchedWords, Effect.ALLOW)
        }
        if (tracing) endLayer(groups)
        return result
    }

    /** Visits the rules of [effect] in every group whose guard matched. Returns true when a visit stopped. */
    private fun visitRules(
        groups: List<RuleGroup>,
        matchedBits: Long,
        matchedWords: LongArray?,
        effect: Effect,
    ): Boolean {
        pass = effect
        for (i in groups.indices) {
            val word = matchedWords?.get(i ushr WORD_SHIFT) ?: matchedBits
            if ((word ushr i) and 1L == 0L) {
                continue
            }
            val group = groups[i]
            groupId = group.id
            if (tracing) beginGroup(i)
            if (group.forEachRule(this)) {
                return true
            }
        }
        return false
    }

    /** Evaluates [entry] when it belongs to the current pass, and stops at the layer's first result. */
    @Suppress("TooGenericExceptionCaught")
    override fun visit(entry: RuleEntry, grantedVia: String?): Boolean {
        val rule = when (entry) {
            is Rule -> entry
            is UnresolvedGrant -> {
                if (pass != Effect.DENY) {
                    return false
                }
                unresolved(entry, grantedVia)
                return true
            }
        }
        if (rule.effect != pass) {
            return false
        }
        val match = try {
            rule.match(request)
        } catch (error: Exception) {
            failed(rule, grantedVia, error)
            return true
        }
        if (match != RuleMatch.MATCHED) {
            return false
        }
        matched(rule, grantedVia)
        return true
    }

    // The only places a rule gives its layer a result. Each keeps the layer's first result and returns the entry's ref.

    /** [rule] of the current pass matched. */
    protected fun matched(rule: Rule, grantedVia: String?): StatementRef {
        val ref = ref(rule, grantedVia)
        if (result == null) {
            result = if (pass == Effect.DENY) Decision.Deny(ref) else Decision.Permit(ref)
        }
        return ref
    }

    /** [rule]'s matcher threw [error]. */
    protected fun failed(rule: Rule, grantedVia: String?, error: Exception): StatementRef {
        val ref = ref(rule, grantedVia)
        if (result == null) {
            result = Decision.Indeterminate(Cause.MatcherFailed(kind, groupId, ref, error))
        }
        return ref
    }

    /**
     * The deny pass reached [grant]: the layer fails here, as 7.0 does (S10a). A DENY matched before it has already
     * decided, and the allow pass never runs after it.
     */
    protected fun unresolved(grant: UnresolvedGrant, grantedVia: String?): StatementRef {
        val ref = ref(grant, grantedVia) as StatementRef.RolePermission
        if (result == null) {
            result = Decision.Indeterminate(Cause.MissingPermissions(ref, grant.error))
        }
        return ref
    }

    protected fun ref(entry: RuleEntry, grantedVia: String?): StatementRef =
        when (entry) {
            is Rule -> if (kind == LayerKind.ROLE) {
                StatementRef.RolePermission(
                    appId = groupId,
                    roleId = roleOf(grantedVia),
                    permissionId = checkNotNull(entry.id) { "A role-layer rule must have a permission id." },
                    name = entry.name,
                )
            } else {
                StatementRef.PolicyStatement(kind, groupId, entry.index, entry.name)
            }

            is UnresolvedGrant -> {
                check(kind == LayerKind.ROLE) { "Only the role layer has unresolved grants." }
                // An undefined permission has no name.
                StatementRef.RolePermission(groupId, roleOf(grantedVia), entry.permissionId, "")
            }
        }

    private fun roleOf(grantedVia: String?): String =
        checkNotNull(grantedVia) { "A role-layer entry must be visited with the role that grants it." }

    // Hooks for the trace, called only when tracing.

    /** The evaluation reached [decision]; the layers from [next] on have not been evaluated. */
    protected open fun finish(decision: Decision, layers: List<LayerInput>, next: Int): Evaluation =
        CoSecEngine.untraced(decision)

    protected open fun layerWithoutGroups(kind: LayerKind, state: LayerState, reason: AbsentReason?, cause: Cause?) =
        Unit

    protected open fun beginLayer(groups: Int) = Unit

    protected open fun guardEvaluated(index: Int, group: RuleGroup, outcome: GuardOutcome, error: Throwable?) = Unit

    protected open fun beginGroup(index: Int) = Unit

    protected open fun endLayer(groups: List<RuleGroup>) = Unit

    private companion object {
        /** log2(64): the word of a group's bit in the bitmap. */
        const val WORD_SHIFT = 6
    }
}
