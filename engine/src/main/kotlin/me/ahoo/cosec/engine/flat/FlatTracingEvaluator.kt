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

import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.BindingRef

/**
 * A [FlatEvaluator] that records the trace.
 *
 * It evaluates the same candidates in the same order and gives the decision through the same [decide] and [failed],
 * but keeps going after the first match and records every statement. Whatever it evaluates once the decision is known
 * is only for the trace: it is marked [CandidateTrace.afterDecision] and records failures instead of throwing them,
 * because the untraced evaluation never calls it, so the decision never depends on the trace level (P2). Only the JVM's
 * own errors and an interruption propagate.
 */
internal class FlatTracingEvaluator(request: FlatRequest, view: SnapshotView, private val level: TraceLevel) :
    FlatEvaluator(request, view) {
    private val candidates = ArrayList<CandidateTrace>()
    private val expired = ArrayList<BindingRef.Bound>()

    /** Set once a pass has decided without a match, so the ALLOW pass after it runs only for the trace. */
    private var decided = false

    /** At [TraceLevel.FULL], the ALLOW pass after a DENY-pass decision lists its statements without evaluating them. */
    private var listing = false

    fun evaluateTraced(): FlatEvaluation {
        val denyDecision = denyPass()
        val decision = if (denyDecision != null) {
            decided = true
            listing = level != TraceLevel.EXHAUSTIVE
            pass = Effect.ALLOW
            enumerate()
            denyDecision
        } else {
            pass = Effect.ALLOW
            enumerate()
            matched ?: CoSecFlatEngine.IMPLICIT_DENY
        }
        return FlatEvaluation(decision, FlatTrace(request.scope, candidates, expired, allUnresolved()))
    }

    private fun traceOnly(): Boolean = decided || matched != null

    override fun catches(error: Throwable): Boolean = if (traceOnly()) recordable(error) else error is Exception

    /** The JVM's own failures and an interruption propagate even after the decision. */
    private fun recordable(error: Throwable): Boolean =
        error !is VirtualMachineError && error !is LinkageError && error !is InterruptedException

    override fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean {
        val statements = policy.statements(pass)
        if (listing) {
            for (statement in statements) record(policy, statement, via, CandidateOutcome.NOT_EVALUATED, null, true)
            return false
        }
        if (statements.isEmpty()) {
            return false
        }
        val guard = policy.guard
        if (guard != null) {
            val traceOnly = traceOnly()
            val result = guard(guard)
            if (result != GUARD_MATCHED) {
                val error = guardError
                if (result == GUARD_ERROR && pass === Effect.DENY && !traceOnly) {
                    failed(policy, statements[0], via, error!!, guard = true)
                }
                val outcome = if (result == GUARD_ERROR) CandidateOutcome.GUARD_ERROR else CandidateOutcome.GUARD_MISS
                for (statement in statements) record(policy, statement, via, outcome, error, traceOnly)
                return false
            }
        }
        for (statement in statements) {
            step(policy, statement, via)
        }
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun step(policy: CompiledPolicy, statement: Rule, via: BindingRef) {
        val traceOnly = traceOnly()
        val match: RuleMatch? = try {
            statement.match(authz)
        } catch (error: Throwable) {
            if (!catches(error)) {
                throw error
            }
            if (pass === Effect.DENY && !traceOnly) failed(policy, statement, via, error, guard = false)
            record(policy, statement, via, CandidateOutcome.ERROR, error, traceOnly)
            return
        }
        val outcome = when (match) {
            RuleMatch.MATCHED -> CandidateOutcome.MATCHED
            RuleMatch.ACTION_MISS -> CandidateOutcome.ACTION_MISS
            RuleMatch.CONDITION_MISS -> CandidateOutcome.CONDITION_MISS
            null -> {
                val error = noMatchResult(policy)
                if (pass === Effect.DENY && !traceOnly) failed(policy, statement, via, error, guard = false)
                record(policy, statement, via, CandidateOutcome.ERROR, error, traceOnly)
                return
            }
        }
        if (outcome == CandidateOutcome.MATCHED && !traceOnly) {
            matched = decide(policy, statement, via)
        }
        record(policy, statement, via, outcome, null, traceOnly)
    }

    private fun record(
        policy: CompiledPolicy,
        statement: Rule,
        via: BindingRef,
        outcome: CandidateOutcome,
        error: Throwable?,
        afterDecision: Boolean,
    ) {
        candidates += CandidateTrace(ref(policy, statement, via), pass, outcome, error, afterDecision)
    }

    override fun expired(via: BindingRef.Bound) {
        if (pass === Effect.DENY) {
            expired += via
        }
    }
}
