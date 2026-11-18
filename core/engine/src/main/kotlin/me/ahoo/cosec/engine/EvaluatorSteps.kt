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
import me.ahoo.cosec.engine.Evaluator.Companion.ACTION_MISS
import me.ahoo.cosec.engine.Evaluator.Companion.CONDITION_MISS
import me.ahoo.cosec.engine.Evaluator.Companion.ERROR
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_ERROR
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_FAILED
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_MISS
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_MISSED
import me.ahoo.cosec.engine.Evaluator.Companion.MATCHED
import me.ahoo.cosec.engine.Evaluator.Companion.RESOURCE_MISS
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.model.condition.CheckedCondition

// One statement's step (RFC 0013 §6): its action, index keys, resource and captures, the policy condition, and the
// condition, pattern by pattern where it reads several.

/**
 * Evaluates [statement] of [policy]: its action, index keys, resource, the policy condition, then its condition,
 * stopping at the first that does not hold (see [CompiledStatement]). On [ERROR] and [GUARD_FAILED], [stepError]
 * is the error. An error that [catches] rejects propagates.
 *
 * The result depends only on the request, never on which statements were evaluated before: the policy condition's
 * result is cached, and a failed resource defers to it.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
internal fun Evaluator.step(policy: CompiledPolicy, statement: CompiledStatement): Int {
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
private fun Evaluator.stepEachPattern(policy: CompiledPolicy, statement: CompiledStatement): Int {
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
private fun Evaluator.holdsUnder(condition: CheckedCondition, captures: Captures): Int {
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
private fun Evaluator.eachPatternMissed(policy: CompiledPolicy, failure: Throwable?, guarded: Boolean): Int {
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
private fun Evaluator.guardOf(policy: CompiledPolicy): Int {
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
// stays: one loop over the patterns, each matched with or without captures inside the try that keeps its failure
@Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
private fun Evaluator.resource(statement: CompiledStatement): Captures? {
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
internal fun Evaluator.ruledOut(statement: CompiledStatement): Int =
    when {
        !statement.matchesAction(authz.action) -> ACTION_MISS
        !statement.admits(this) -> CONDITION_MISS
        else -> RESOURCE_MISS
    }
