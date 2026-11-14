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

// The statement step: a candidate statement against the request, its policy guard and its resource captures.

/**
 * The captures a rule's key reads: those of the first pattern, in declared order, that matches and under whose
 * captures the rule's condition holds (`ALG-10`). The rule matched, so one does; a pattern whose condition fails is
 * not one under which it holds.
 */
@Suppress("TooGenericExceptionCaught")
internal fun FlatEvaluator.keyCaptures(statement: CompiledStatement): Captures? {
    val condition = statement.condition
    for (pattern in statement.resources) {
        val captures = pattern.match(target, this) ?: continue
        if (!statement.eachPattern || condition == null) {
            return captures
        }
        this.captures = captures
        val holds = try {
            condition.evaluate(this)
        } catch (thrown: Throwable) {
            if (!catches(thrown)) {
                throw thrown
            }
            false
        } finally {
            this.captures = null
        }
        if (holds) {
            return captures
        }
    }
    return null
}

/** A boundary statement whose external attribute is missing and says `onMissing nomatch` is a miss. */
internal fun FlatEvaluator.lapses(statement: CompiledStatement, error: Throwable?): Boolean =
    error is AttributeMissing && statement.onMissing == OnMissing.NOMATCH

/**
 * Evaluates [statement] and acts on the result: [DECIDED] on a match, [NEXT_POLICY] when the policy condition does
 * not hold (none of the policy's statements can match), else [NEXT_STATEMENT].
 */
internal fun FlatEvaluator.visit(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): Int {
    when (step(policy, statement)) {
        MATCHED -> {
            matched = decide(policy, statement, via)
            return DECIDED
        }

        ERROR -> erred(policy, statement, via, stepError!!, guard = false)
        GUARD_FAILED -> {
            val error = stepError!!
            erred(policy, statement, via, error, guard = true)
            // A missing attribute that this statement treats as no match says nothing about the policy's others.
            return if (error is AttributeMissing && statement.onMissing == OnMissing.NOMATCH) NEXT_STATEMENT else NEXT_POLICY
        }

        GUARD_MISS -> return NEXT_POLICY
    }
    return NEXT_STATEMENT
}

/**
 * Evaluates [statement] of [policy]: its action, index keys, resource, the policy condition, then its condition,
 * stopping at the first that does not hold (see [CompiledStatement]). On [ERROR] and [GUARD_FAILED], [stepError]
 * is the error. An error that [catches] rejects propagates.
 *
 * The result depends only on the request, never on which statements were evaluated before: the policy condition's
 * result is cached, and a failed resource defers to it.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
internal fun FlatEvaluator.step(policy: CompiledPolicy, statement: CompiledStatement): Int {
    if (!statement.matchesAction(authz.action)) {
        return ACTION_MISS
    }
    if (!statement.admits(this)) {
        return CONDITION_MISS
    }
    if (statement.eachPattern) {
        return stepEachPattern(policy, statement)
    }
    var failure: Throwable? = null
    val captures = try {
        resource(statement)
    } catch (thrown: Throwable) {
        if (!catches(thrown)) {
            throw thrown
        }
        failure = thrown
        null
    }
    if (captures == null && failure == null) {
        return RESOURCE_MISS
    }
    // Consulted even when the resource failed: a policy condition that does not hold rules out every statement of
    // the policy, so the untraced evaluation may skip the rest of them and still decide as the trace does.
    val guard = guardOf(policy)
    if (guard != MATCHED) {
        return guard
    }
    if (failure != null) {
        stepError = failure
        return ERROR
    }
    val condition = statement.condition ?: return MATCHED
    this.captures = captures
    return try {
        if (condition.evaluate(this)) MATCHED else CONDITION_MISS
    } catch (thrown: Throwable) {
        if (!catches(thrown)) {
            throw thrown
        }
        stepError = thrown
        ERROR
    } finally {
        this.captures = null
    }
}

/**
 * [step] for a statement whose condition reads captures of several patterns (`ALG-10`): it means what one statement
 * per pattern would. The condition is tested against each matching pattern's captures in declared order, and any
 * one holding is a match. Otherwise the first failure, of matching a pattern or of the condition, in declared
 * order, is the statement's error; a policy condition that does not hold, or fails, decides before either, as it
 * would for each of those statements.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal fun FlatEvaluator.stepEachPattern(policy: CompiledPolicy, statement: CompiledStatement): Int {
    val condition = statement.condition!!
    val globs = statement.globUnits > 0
    var failure: Throwable? = null
    var guarded = false
    for (pattern in statement.resources) {
        val captures = try {
            if (globs) pattern.match(target, this) else pattern.match(target)
        } catch (thrown: Throwable) {
            // As [step] treats the failure of [resource]: the evaluation's rule, traced or not.
            if (!catches(thrown)) {
                throw thrown
            }
            failure = failure ?: thrown
            null
        } ?: continue
        if (!guarded) {
            val guard = guardOf(policy)
            if (guard != MATCHED) {
                return guard
            }
            guarded = true
        }
        when (holdsUnder(condition, captures)) {
            MATCHED -> return MATCHED
            ERROR -> failure = failure ?: stepError
        }
    }
    return eachPatternMissed(policy, failure, guarded)
}

/** [MATCHED] when [condition] holds under [captures], [CONDITION_MISS] when not, [ERROR] with [stepError] when it threw. */
@Suppress("TooGenericExceptionCaught")
internal fun FlatEvaluator.holdsUnder(condition: CheckedCondition, captures: Captures): Int {
    this.captures = captures
    return try {
        if (condition.evaluate(this)) MATCHED else CONDITION_MISS
    } catch (thrown: Throwable) {
        if (!catches(thrown)) {
            throw thrown
        }
        stepError = thrown
        ERROR
    } finally {
        this.captures = null
    }
}

/**
 * [stepEachPattern]'s result when no pattern held: a miss without [failure]; otherwise the failure, unless the policy
 * condition, consulted if no pattern [guarded] it yet, rules the statement out, as in [step].
 */
internal fun FlatEvaluator.eachPatternMissed(policy: CompiledPolicy, failure: Throwable?, guarded: Boolean): Int {
    if (failure == null) {
        return if (guarded) CONDITION_MISS else RESOURCE_MISS
    }
    if (!guarded) {
        val guard = guardOf(policy)
        if (guard != MATCHED) {
            return guard
        }
    }
    stepError = failure
    return ERROR
}

/** [MATCHED] when [policy] has no condition or it holds, else [GUARD_MISS] or [GUARD_FAILED] with [stepError]. */
internal fun FlatEvaluator.guardOf(policy: CompiledPolicy): Int {
    val guard = policy.condition ?: return MATCHED
    return when (guard(guard)) {
        GUARD_MISSED -> GUARD_MISS
        GUARD_ERROR -> {
            stepError = guardError
            GUARD_FAILED
        }

        else -> MATCHED
    }
}

/**
 * The captures of the first resource pattern that matches ([Captures.NONE] when the condition reads none), or
 * null. A pattern that fails (a path that does not decode) fails the statement unless another one matches.
 */
@Suppress("TooGenericExceptionCaught")
internal fun FlatEvaluator.resource(statement: CompiledStatement): Captures? {
    var failure: Exception? = null
    // Only glob segments use the evaluation's table: a statement without one matches through the plain calls, as
    // before the table, one call less deep on the hot path.
    val globs = statement.globUnits > 0
    for (pattern in statement.resources) {
        try {
            if (!statement.captures) {
                if (if (globs) pattern.matches(target, this) else pattern.matches(target)) {
                    return Captures.NONE
                }
            } else {
                (if (globs) pattern.match(target, this) else pattern.match(target))?.let { return it }
            }
        } catch (error: Exception) {
            failure = failure ?: error
        }
    }
    if (failure != null) {
        throw failure
    }
    return null
}

/** Whether the index or the scan can skip [statement]: what [step] checks before calling host code. */
internal fun FlatEvaluator.ruledOut(statement: CompiledStatement): Int =
    when {
        !statement.matchesAction(authz.action) -> ACTION_MISS
        !statement.admits(this) -> CONDITION_MISS
        else -> RESOURCE_MISS
    }
