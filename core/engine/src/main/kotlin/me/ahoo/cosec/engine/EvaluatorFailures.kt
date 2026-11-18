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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef

// What a match, an error or a failed statement leaves (RFC 0013 §6): the decision of a match, the first error, the
// skipped ALLOW, and what the request could not resolve.

/** A boundary statement whose external attribute is missing and says `onMissing nomatch` is a miss. */
internal fun Evaluator.lapses(statement: CompiledStatement, error: Throwable?): Boolean =
    error is AttributeMissing && statement.onMissing == OnMissing.NOMATCH

/** The first of what the request could not resolve, in a fixed order: the caller's, then the engine's. */
internal fun Evaluator.unresolved(): Cause? =
    view.unresolved.firstOrNull()
        ?: view.notLoaded(request.scope)
        ?: missing?.let { Cause.MissingPolicies(it) }
        ?: ambiguous?.let { Cause.AmbiguousPolicy(it) }

/** Everything unresolved, for the trace. */
internal fun Evaluator.allUnresolved(): List<Cause> =
    buildList {
        addAll(view.unresolved)
        view.notLoaded(request.scope)?.let(::add)
        missing?.let { add(Cause.MissingPolicies(it)) }
        ambiguous?.let { add(Cause.AmbiguousPolicy(it)) }
    }

internal fun Evaluator.decide(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): Decision {
    val ref = ref(policy, statement, via)
    return if (pass === Effect.DENY) Decision.Deny(ref) else Decision.Permit(ref)
}

/**
 * [statement], or the policy condition reached through it, failed with [error]. A missing external attribute is
 * decided by the statement's [OnMissing] (RFC 0006 §5.4.3): `nomatch` is a miss; otherwise a DENY errs, and an
 * ALLOW is skipped and remembered. Any other error of a DENY is kept; of an ALLOW, it is no match (C1a).
 */
internal fun Evaluator.erred(
    policy: CompiledPolicy,
    statement: CompiledStatement,
    via: BindingRef,
    error: Throwable,
    guard: Boolean
) {
    val missing = error is AttributeMissing
    if (missing && statement.onMissing == OnMissing.NOMATCH) {
        return
    }
    if (pass === Effect.DENY) {
        failed(policy, statement, via, error, guard)
    } else if (missing && skipped == null) {
        skipped = Cause.AttributeUnavailable(setOf((error as AttributeMissing).path), ref(policy, statement, via))
    }
}

/** A DENY statement, or the condition reached through it, failed. Keeps the first. */
private fun Evaluator.failed(
    policy: CompiledPolicy,
    statement: CompiledStatement,
    via: BindingRef,
    error: Throwable,
    guard: Boolean,
) {
    if (errorAt == null) {
        errorAt = ref(policy, statement, via)
        errorInGuard = guard
        this.error = error
    }
}

internal fun Evaluator.ref(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): StatementRef.Policy =
    StatementRef.Policy(PolicyId(policy.id), statement.index, statement.name, via)
