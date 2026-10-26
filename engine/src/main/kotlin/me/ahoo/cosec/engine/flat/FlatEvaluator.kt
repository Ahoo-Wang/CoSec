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

import me.ahoo.cosec.engine.Guard
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef

/**
 * One evaluation over bindings: the flat algorithm of RFC 0004 §6.1.
 *
 * 1. The DENY pass visits every candidate's DENY statements. The first match is the decision: a DENY wins over errors,
 *    missing policies and documents that could not be read.
 * 2. Without a match, an erroring DENY (statement or the policy condition reached through it) makes the decision
 *    indeterminate, and so does anything unresolved: there could be a DENY nobody could check.
 * 3. Only then the ALLOW pass: the first match permits; an erroring ALLOW counts as no match (C1a); nothing matched is
 *    an implicit deny.
 *
 * So the decision depends on the set of candidates, never on their order (P8): only which statement is reported does.
 *
 * Untraced, it stops at the decision and records nothing. Besides itself and the decision, it allocates only for
 * policies with a condition (a small cache, so each condition runs at most once) and on error paths. It is the
 * [CandidateVisitor] of its own enumeration, so visiting allocates no visitor. [FlatTracingEvaluator] overrides only
 * [candidate], the step per candidate, so no trace code sits in the untraced one (see `Evaluator` for why).
 */
internal open class FlatEvaluator(protected val request: FlatRequest, protected val view: SnapshotView) :
    CandidateVisitor {
    protected val authz: AuthzRequest = request.request

    /** The effect whose statements the current pass evaluates. */
    protected var pass: Effect = Effect.DENY

    /** The pass's first match. */
    protected var matched: Decision? = null

    // The first error of the DENY pass.
    private var errorAt: StatementRef.Policy? = null
    private var errorInGuard = false
    private var error: Throwable? = null

    private var missing: MutableSet<PolicyId>? = null
    private var ambiguous: MutableSet<PolicyId>? = null

    // Conditions evaluated so far, by identity, and their results; allocated on the first condition.
    private var guards: Array<Guard?>? = null
    private var guardResults: IntArray? = null
    private var guardErrors: Array<Throwable?>? = null
    private var guardCount = 0

    /** The error of the condition [guard] returned [GUARD_ERROR] for last. */
    protected var guardError: Throwable? = null

    fun evaluate(): Decision {
        val decision = denyPass()
        if (decision != null) {
            return decision
        }
        pass = Effect.ALLOW
        enumerate()
        return matched ?: CoSecFlatEngine.IMPLICIT_DENY
    }

    /** Runs the DENY pass and returns its decision, or null when the ALLOW pass decides. */
    protected fun denyPass(): Decision? {
        pass = Effect.DENY
        enumerate()
        matched?.let { return it }
        errorAt?.let { return Decision.Indeterminate(Cause.MatcherFailed(it, errorInGuard, error!!)) }
        return unresolved()?.let { Decision.Indeterminate(it) }
    }

    /** Visits the candidates. Open for the property test that shuffles them (P8). */
    protected open fun enumerate(): Boolean = CandidateOrder.forEach(request, view, this)

    /** The first of what the request could not resolve, in a fixed order: the caller's, then the engine's. */
    private fun unresolved(): Cause? =
        view.unresolved.firstOrNull()
            ?: view.notLoaded(request.scope)
            ?: missing?.let { Cause.MissingPolicies(it) }
            ?: ambiguous?.let { Cause.AmbiguousPolicy(it) }

    /** Everything unresolved, for the trace. */
    protected fun allUnresolved(): List<Cause> =
        buildList {
            addAll(view.unresolved)
            view.notLoaded(request.scope)?.let(::add)
            missing?.let { add(Cause.MissingPolicies(it)) }
            ambiguous?.let { add(Cause.AmbiguousPolicy(it)) }
        }

    @Suppress("TooGenericExceptionCaught")
    override fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean {
        val statements = policy.statements(pass)
        if (statements.isEmpty()) {
            return false
        }
        val guard = policy.guard
        if (guard != null) {
            val result = guard(guard)
            if (result != GUARD_MATCHED) {
                if (result == GUARD_ERROR && pass === Effect.DENY) {
                    failed(policy, statements[0], via, guardError!!, guard = true)
                }
                return false
            }
        }
        for (statement in statements) {
            val match: RuleMatch? = try {
                statement.match(authz)
            } catch (error: Exception) {
                if (pass === Effect.DENY) failed(policy, statement, via, error, guard = false)
                continue
            }
            // Compared rather than switched on: a `when` over the enum costs a table lookup per statement.
            if (match === RuleMatch.MATCHED) {
                matched = decide(policy, statement, via)
                return true
            }
            if (match == null && pass === Effect.DENY) {
                failed(policy, statement, via, noMatchResult(policy), guard = false)
            }
        }
        return false
    }

    override fun expired(via: BindingRef.Bound) = Unit

    override fun missing(policyId: String) {
        // Both passes enumerate the same candidates; the DENY pass records.
        if (pass === Effect.DENY) {
            (missing ?: LinkedHashSet<PolicyId>().also { missing = it }) += PolicyId(policyId)
        }
    }

    override fun ambiguous(policyId: String) {
        if (pass === Effect.DENY) {
            (ambiguous ?: LinkedHashSet<PolicyId>().also { ambiguous = it }) += PolicyId(policyId)
        }
    }

    /**
     * The result of [guard], evaluating it on first use; on [GUARD_ERROR], [guardError] is its error. An error that
     * [catches] rejects propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun guard(guard: Guard): Int {
        val guards = guards
        if (guards != null) {
            for (i in 0 until guardCount) {
                if (guards[i] === guard) {
                    guardError = guardErrors?.get(i)
                    return guardResults!![i]
                }
            }
        }
        var error: Throwable? = null
        val result = try {
            if (guard.matches(authz)) GUARD_MATCHED else GUARD_MISSED
        } catch (thrown: Throwable) {
            if (!catches(thrown)) {
                throw thrown
            }
            error = thrown
            GUARD_ERROR
        }
        remember(guard, result, error)
        guardError = error
        return result
    }

    private fun remember(guard: Guard, result: Int, error: Throwable?) {
        var guards = guards
        var results = guardResults
        if (guards == null || results == null) {
            guards = arrayOfNulls(INITIAL_GUARDS)
            results = IntArray(INITIAL_GUARDS)
        } else if (guardCount == guards.size) {
            guards = guards.copyOf(guardCount * 2)
            results = results.copyOf(guardCount * 2)
            guardErrors = guardErrors?.copyOf(guardCount * 2)
        }
        this.guards = guards
        guardResults = results
        if (error != null) {
            val errors = guardErrors ?: arrayOfNulls<Throwable>(guards.size).also { guardErrors = it }
            errors[guardCount] = error
        }
        guards[guardCount] = guard
        results[guardCount] = result
        guardCount++
    }

    /** Whether an error thrown by a matcher is that matcher's error rather than the evaluation's. */
    protected open fun catches(error: Throwable): Boolean = error is Exception

    protected fun decide(policy: CompiledPolicy, statement: Rule, via: BindingRef): Decision {
        val ref = ref(policy, statement, via)
        return if (pass === Effect.DENY) Decision.Deny(ref) else Decision.Permit(ref)
    }

    /** A DENY statement, or the condition reached through it, failed. Keeps the first. */
    protected fun failed(policy: CompiledPolicy, statement: Rule, via: BindingRef, error: Throwable, guard: Boolean) {
        if (errorAt == null) {
            errorAt = ref(policy, statement, via)
            errorInGuard = guard
            this.error = error
        }
    }

    /** A null from a Java matcher is a broken contract, which fails the statement: never a miss. */
    protected fun noMatchResult(policy: CompiledPolicy): IllegalStateException =
        IllegalStateException("A statement of policy ${policy.id} returned no match result")

    protected fun ref(policy: CompiledPolicy, statement: Rule, via: BindingRef): StatementRef.Policy =
        StatementRef.Policy(PolicyId(policy.id), statement.index, statement.name, via)

    protected companion object {
        const val GUARD_MATCHED = 1
        const val GUARD_MISSED = 2
        const val GUARD_ERROR = 3
        private const val INITIAL_GUARDS = 4
    }
}
