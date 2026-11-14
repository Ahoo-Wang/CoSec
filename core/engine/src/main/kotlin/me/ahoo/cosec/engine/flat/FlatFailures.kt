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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.EvaluationScratch
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.engine.pattern.ScratchSource
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.StatementRef
import java.util.IdentityHashMap
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.MATCHED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.ACTION_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.CONDITION_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.RESOURCE_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_FAILED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.ERROR
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NEXT_STATEMENT
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NEXT_POLICY
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.DECIDED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MATCHED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MISSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_ERROR
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.INITIAL_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.SCAN_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.MAP_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NOT_REMEMBERED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.INITIAL_LISTS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.WORD_BITS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.WORD_SHIFT
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_UNPARSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_PARSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_MALFORMED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.EMPTY

// What a matched, erring or failing statement leaves for the decision.

internal fun FlatEvaluator.decide(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): Decision {
    val ref = ref(policy, statement, via)
    return if (pass === Effect.DENY) Decision.Deny(ref) else Decision.Permit(ref)
}

/**
 * [statement], or the policy condition reached through it, failed with [error]. A missing external attribute is
 * decided by the statement's [OnMissing] (RFC 0006 §5.4.3): `nomatch` is a miss; otherwise a DENY errs, and an
 * ALLOW is skipped and remembered. Any other error of a DENY is kept; of an ALLOW, it is no match (C1a).
 */
internal fun FlatEvaluator.erred(
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
internal fun FlatEvaluator.failed(
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

internal fun FlatEvaluator.ref(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): StatementRef.Policy =
    StatementRef.Policy(PolicyId(policy.id), statement.index, statement.name, via)
