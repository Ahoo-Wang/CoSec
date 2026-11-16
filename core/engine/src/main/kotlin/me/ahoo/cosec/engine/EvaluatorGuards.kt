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
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_ERROR
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_MATCHED
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_MISSED
import me.ahoo.cosec.engine.Evaluator.Companion.INITIAL_GUARDS
import me.ahoo.cosec.engine.Evaluator.Companion.MAP_GUARDS
import me.ahoo.cosec.engine.Evaluator.Companion.NOT_REMEMBERED
import me.ahoo.cosec.engine.Evaluator.Companion.SCAN_GUARDS
import me.ahoo.cosec.model.condition.CheckedCondition
import java.util.IdentityHashMap

// The memo of policy conditions (RFC 0013 §6): each evaluated at most once per evaluation, found by identity.

/**
 * The result of [guard], evaluating it on first use; on [GUARD_ERROR], [guardError] is its error. An error that
 * [catches] rejects propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal fun Evaluator.guard(guard: CheckedCondition): Int {
    val guards = guards
    if (guards != null) {
        for (i in 0 until guardCount) {
            if (guards[i] === guard) {
                guardError = guardErrors?.get(i)
                return guardResults!![i]
            }
        }
    } else {
        // Only once there are more conditions than SCAN_GUARDS: the arrays are gone.
        val remembered = recalled(guard)
        if (remembered != NOT_REMEMBERED) {
            return remembered
        }
    }
    var error: Throwable? = null
    val result = try {
        if (guard.evaluate(this)) GUARD_MATCHED else GUARD_MISSED
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

/** The result [guard] had in this evaluation, with [guardError] set, or [NOT_REMEMBERED]; for the map. */
private fun Evaluator.recalled(guard: CheckedCondition): Int =
    when (val outcome = guardMap?.get(guard)) {
        null -> NOT_REMEMBERED
        is Throwable -> {
            guardError = outcome
            GUARD_ERROR
        }

        else -> {
            guardError = null
            outcome as Int
        }
    }

private fun Evaluator.remember(guard: CheckedCondition, result: Int, error: Throwable?) {
    var guards = guards
    var results = guardResults
    if (guards == null || results == null) {
        val map = guardMap
        if (map != null) {
            map[guard] = error ?: result
            return
        }
        guards = arrayOfNulls(INITIAL_GUARDS)
        results = IntArray(INITIAL_GUARDS)
    } else if (guardCount == guards.size) {
        if (guardCount == SCAN_GUARDS) {
            moveGuardsToMap(guard, error ?: result)
            return
        }
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

/** Remembers the [SCAN_GUARDS] conditions of the arrays and [guard] in a map, and drops the arrays. */
private fun Evaluator.moveGuardsToMap(guard: CheckedCondition, outcome: Any) {
    val map = IdentityHashMap<CheckedCondition, Any>(MAP_GUARDS)
    for (i in 0 until guardCount) {
        map[guards!![i]] = guardErrors?.get(i) ?: guardResults!![i]
    }
    map[guard] = outcome
    guardMap = map
    guards = null
    guardResults = null
    guardErrors = null
}
