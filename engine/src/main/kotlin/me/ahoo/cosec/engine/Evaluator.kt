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
        val matchedWords = if (size > Long.SIZE_BITS) LongArray((size + Long.SIZE_BITS - 1) / Long.SIZE_BITS) else null
        for (i in 0 until size) {
            val group = groups[i]
            val matched = try {
                group.guard.matches(request)
            } catch (error: Throwable) {
                if (guardThrew(i, group, error)) {
                    return result
                }
                continue
            }
            if (matched) {
                matchedBits = mark(matchedBits, matchedWords, i)
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

    /** Sets group [index]'s bit: in [words] when there are more than 64 groups, else in the returned bits. */
    private fun mark(bits: Long, words: LongArray?, index: Int): Long {
        if (words == null) {
            return bits or (1L shl index)
        }
        val word = index ushr WORD_SHIFT
        words[word] = words[word] or (1L shl index)
        return bits
    }

    /**
     * Group [index]'s guard threw [error]. An Exception fails the layer; any other Throwable propagates, unless the
     * trace runs the guard only after the decision. An interruption is never recorded: the caller asked the thread to
     * stop. Returns true when the layer ends here, which it does untraced.
     */
    private fun guardThrew(index: Int, group: RuleGroup, error: Throwable): Boolean {
        val propagates = if (error is Exception) {
            error is InterruptedException && evaluatingForTraceOnly()
        } else {
            !recordsAfterDecision(error)
        }
        if (propagates) {
            throw error
        }
        return guardFailed(index, group, error)
    }

    /** Group [index]'s guard threw [error]. Returns true when that ends the layer, which it does untraced. */
    private fun guardFailed(index: Int, group: RuleGroup, error: Throwable): Boolean {
        if (result == null) {
            result = Decision.Indeterminate(Cause.MatcherFailed(kind, group.id, null, error))
        }
        if (!tracing) {
            return true
        }
        guardEvaluated(index, group, GuardOutcome.ERROR, error)
        return false
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
            val tracing = tracing
            if (tracing) beginGroup(i)
            val stopped = group.forEachRule(this)
            if (tracing) endGroup()
            // A group must stop exactly when its visitor asks to: untraced, that is when the layer got its result
            // (it had none on entry); traced, never. Trusting a group that claims otherwise would skip later DENYs.
            if (if (tracing) stopped else stopped != (result != null)) {
                throw brokenContract(
                    "Group $groupId returned $stopped from forEachRule, but its visitor did not ask to stop"
                )
            }
            if (stopped) {
                return true
            }
        }
        return false
    }

    /** Evaluates [entry] when it belongs to the current pass, and stops at the layer's first result. */
    @Suppress("TooGenericExceptionCaught")
    override fun visit(entry: RuleEntry, grantedVia: String?): Boolean {
        checkEntry(entry, grantedVia)
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
        val effect: Effect? = rule.effect
        if (effect !== pass) {
            // A null effect from a Java adapter would otherwise belong to no pass and be skipped silently.
            if (effect == null) {
                throw brokenEntry(entry, grantedVia)
            }
            return false
        }
        val match: RuleMatch? = try {
            rule.match(request)
        } catch (error: Exception) {
            failed(rule, grantedVia, error)
            return true
        }
        // Compared rather than switched on: a `when` over the enum costs a table lookup per rule on the hot path.
        if (checkMatch(match) !== RuleMatch.MATCHED) {
            return false
        }
        matched(rule, grantedVia)
        return true
    }

    /** A null from a Java matcher is a broken contract, never a miss. */
    protected fun checkMatch(match: RuleMatch?): RuleMatch =
        match ?: throw brokenContract("A rule of group $groupId returned no match result.")

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
    protected fun failed(rule: Rule, grantedVia: String?, error: Throwable): StatementRef {
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

    /**
     * Fails on an entry its group should never have visited: in the role layer, without the granting role or a
     * permission id; elsewhere, with a granting role or as an unresolved grant. Both evaluators check every entry they
     * visit, so a broken adapter fails the same way at every [TraceLevel] up to the decision. Untraced, entries after
     * the decision are not visited, so only the trace can find a broken one there.
     */
    protected fun checkEntry(entry: RuleEntry, grantedVia: String?) {
        val valid = if (kind === LayerKind.ROLE) {
            grantedVia != null && (entry !is Rule || entry.id != null)
        } else {
            grantedVia == null && entry is Rule
        }
        if (!valid) {
            throw brokenEntry(entry, grantedVia)
        }
    }

    /** [checkEntry], and also that a rule has an effect, which the untraced [visit] checks where it reads it. */
    protected fun checkEntryAndEffect(entry: RuleEntry, grantedVia: String?) {
        checkEntry(entry, grantedVia)
        if (entry is Rule && (entry.effect as Effect?) == null) {
            throw brokenEntry(entry, grantedVia)
        }
    }

    protected fun brokenEntry(entry: RuleEntry, grantedVia: String?): IllegalStateException =
        brokenContract(
            when {
                entry is Rule && (entry.effect as Effect?) == null -> "A rule must have an effect"
                kind != LayerKind.ROLE && entry is UnresolvedGrant -> "Only the role layer has unresolved grants"
                kind != LayerKind.ROLE -> "A $kind-layer rule must be visited without a granting role, got $grantedVia"
                grantedVia == null -> "A role-layer entry must be visited with the role that grants it"
                else -> "A role-layer rule must have a permission id"
            } + " (group $groupId).",
        )

    /**
     * A group or entry broke the adapter contract of [RuleGroup]: no decision can be trusted, so the evaluation fails.
     * Returned rather than thrown, so each caller says `throw` and coverage tools record the line as run.
     */
    private fun brokenContract(message: String): IllegalStateException = IllegalStateException(message)

    /** The entry's reference; [checkEntry] has accepted it. */
    protected fun ref(entry: RuleEntry, grantedVia: String?): StatementRef =
        when (entry) {
            is Rule -> if (kind == LayerKind.ROLE) {
                StatementRef.RolePermission(groupId, grantedVia!!, entry.id!!, entry.name)
            } else {
                StatementRef.PolicyStatement(kind, groupId, entry.index, entry.name)
            }

            // An undefined permission has no name.
            is UnresolvedGrant -> StatementRef.RolePermission(groupId, grantedVia!!, entry.permissionId, "")
        }

    /**
     * Whether a traced evaluation records [error], thrown by a guard or rule it evaluates only for the trace, instead of
     * letting it propagate. Untraced, nothing is evaluated after the decision, so this is never asked.
     */
    protected open fun recordsAfterDecision(error: Throwable): Boolean = false

    /** Whether what is evaluated now is evaluated only for the trace. Never untraced. */
    protected open fun evaluatingForTraceOnly(): Boolean = false

    // Hooks for the trace, called only when tracing.

    /** The evaluation reached [decision]; the layers from [next] on have not been evaluated. */
    protected open fun finish(decision: Decision, layers: List<LayerInput>, next: Int): Evaluation =
        CoSecEngine.untraced(decision)

    protected open fun layerWithoutGroups(kind: LayerKind, state: LayerState, reason: AbsentReason?, cause: Cause?) =
        Unit

    protected open fun beginLayer(groups: Int) = Unit

    protected open fun guardEvaluated(index: Int, group: RuleGroup, outcome: GuardOutcome, error: Throwable?) = Unit

    protected open fun beginGroup(index: Int) = Unit

    /** The group begun last has been visited for the current pass. */
    protected open fun endGroup() = Unit

    protected open fun endLayer(groups: List<RuleGroup>) = Unit

    private companion object {
        /** log2(64): the word of a group's bit in the bitmap. */
        const val WORD_SHIFT = 6
    }
}
