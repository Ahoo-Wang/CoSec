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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.PatternContainment
import me.ahoo.cosec.engine.pattern.ResourceContainment
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Containment

/** Narrowing (§7.4.4): a changed ALLOW that only narrows one of the policy's previous ALLOWs grants nothing. */
internal class GrantNarrowing(private val checker: ConditionChecker, private val equality: GrantEquality) {
    /**
     * Whether [statement] (with [condition], its own and its policy's) narrows [previous] (with [previousCondition]):
     * the same effect, its actions and resources contained in the previous ones, and its condition implying the
     * previous one (§7.4.4). Then it matches no request the previous statement did not (A6).
     */
    fun narrows(
        statement: GrantStatement,
        condition: Condition?,
        previous: GrantStatement,
        previousCondition: Condition?,
    ): Boolean {
        if (statement.effect != previous.effect) {
            return false
        }
        if (!statement.actions.all { action ->
                previous.actions.any {
                    PatternContainment.contains(it, action) == Containment.YES
                }
            }
        ) {
            return false
        }
        val checked = condition?.let { equality.check(it) ?: return false }
        return statement.resources.all { resource ->
            previous.resources.any { outer ->
                val containment = PatternContainment.contains(outer, resource) as? ResourceContainment.Contained
                containment != null && GrantImplication.implied(checked, previousCondition) {
                    Specialization(checker, { null }, renames = containment.variables)
                }
            }
        }
    }

    /** A statement written as one of the previous ones, under the same policy condition, is unchanged. */
    fun narrowsAny(policy: GrantPolicy, statement: GrantStatement, previous: GrantPolicy): Boolean {
        val condition = both(policy.condition, statement.condition)
        return previous.statements.any {
            val previousCondition = both(previous.condition, it.condition)
            (equality.sameStatement(statement, it) && equality.sameCondition(condition, previousCondition)) ||
                narrows(statement, condition, it, previousCondition)
        }
    }
}
