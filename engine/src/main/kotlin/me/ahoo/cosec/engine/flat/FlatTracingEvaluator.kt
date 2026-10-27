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

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.BindingRef

/**
 * A [FlatEvaluator] that records the trace.
 *
 * It evaluates the same candidates in the same order with the same [step] and gives the decision through the same
 * [decide] and [failed], but keeps going after the first match and records every statement. A statement the index
 * left out is recorded by what ruled it out, without evaluating anything that calls host code. Whatever it evaluates
 * once the decision is known is only for the trace: it is marked [CandidateTrace.afterDecision] and records failures
 * instead of throwing them, because the untraced evaluation never calls it, so the decision never depends on the trace
 * level (P2). Only the JVM's own errors and an interruption propagate.
 */
internal class FlatTracingEvaluator(request: FlatRequest, view: SnapshotView, private val level: TraceLevel) :
    FlatEvaluator(request, view) {
    private val candidates = ArrayList<CandidateTrace>()
    private val expired = ArrayList<BindingRef.Bound>()
    private val boundary = ArrayList<CandidateTrace>()

    /** Set while the boundary decides: its errors are caught as the untraced evaluation catches them. */
    private var inBoundary = false

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
            withObligations(allowDecision().let { if (view.bounded) withinBoundary(it) else it })
        }
        return FlatEvaluation(decision, FlatTrace(request.scope, candidates, expired, allUnresolved(), boundary))
    }

    private fun traceOnly(): Boolean = !inBoundary && (decided || matched != null)

    override fun catches(error: Throwable): Boolean = if (traceOnly()) recordable(error) else error is Exception

    override fun boundaryStep(boundary: CompiledPolicy, statement: CompiledStatement): Int {
        inBoundary = true
        val result = try {
            step(boundary, statement)
        } finally {
            inBoundary = false
        }
        val error = if (result == ERROR || result == GUARD_FAILED) stepError else null
        val via = BindingRef.Boundary(request.scope.tenant)
        this.boundary += CandidateTrace(ref(boundary, statement, via), Effect.ALLOW, OUTCOMES[result], error)
        return result
    }

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
        val index = policy.index(pass)
        val positions = if (index == null) null else candidates(index)
        var next = 0
        for ((position, statement) in statements.withIndex()) {
            if (positions == null || (next < positions.size && positions[next] == position)) {
                next++
                step(policy, statement, via)
            } else {
                record(policy, statement, via, skipped(statement), null, traceOnly())
            }
        }
        return false
    }

    /** What ruled out a statement the index left out; the index never leaves out one that passes these (I1). */
    private fun skipped(statement: CompiledStatement): CandidateOutcome {
        val outcome = ruledOut(statement)
        check(outcome != RESOURCE_MISS || statement.resources.none { it.matches(target) }) {
            "The index left out statement ${statement.name}, whose resource matches"
        }
        return OUTCOMES[outcome]
    }

    private fun step(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef) {
        val traceOnly = traceOnly()
        val result = step(policy, statement)
        var error: Throwable? = null
        when (result) {
            MATCHED -> if (!traceOnly) matched = decide(policy, statement, via)
            ERROR, GUARD_FAILED -> {
                error = stepError
                if (!traceOnly) erred(policy, statement, via, error!!, result == GUARD_FAILED)
            }
        }
        record(policy, statement, via, OUTCOMES[result], error, traceOnly)
    }

    private fun record(
        policy: CompiledPolicy,
        statement: CompiledStatement,
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

    private companion object {
        /** By the result of [step]. */
        val OUTCOMES = arrayOf(
            CandidateOutcome.MATCHED,
            CandidateOutcome.ACTION_MISS,
            CandidateOutcome.CONDITION_MISS,
            CandidateOutcome.RESOURCE_MISS,
            CandidateOutcome.GUARD_MISS,
            CandidateOutcome.GUARD_ERROR,
            CandidateOutcome.ERROR,
        )
    }
}
