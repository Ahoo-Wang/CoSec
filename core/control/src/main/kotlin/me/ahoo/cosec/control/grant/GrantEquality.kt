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
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.condition.CheckResult
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand

/**
 * When two parts of a change are the same: bindings, statements, policies and conditions (§7.4.4). Narrowing and the check
 * of protected entries both read it, with the one checker the analysis runs, so what one calls unchanged the other does.
 */
internal class GrantEquality(private val checker: ConditionChecker) {
    /** The checked form of [condition], or `null` when it does not check. */
    fun check(condition: Condition): CheckedCondition? =
        (checker.check(condition) as? CheckResult.Valid)?.condition

    fun samePolicy(a: GrantPolicy, b: GrantPolicy): Boolean =
        a.attachable == b.attachable && a.protected == b.protected && sameCondition(a.condition, b.condition) &&
            a.statements.size == b.statements.size &&
            a.statements.indices.all { sameStatement(a.statements[it], b.statements[it]) }

    fun sameStatement(a: GrantStatement, b: GrantStatement): Boolean =
        a.effect == b.effect && a.name == b.name && a.actions == b.actions && a.resources == b.resources &&
            a.onMissing == b.onMissing && sameCondition(a.condition, b.condition)

    /**
     * Conditions compare by their canonical rendering; one that does not check (an attribute outside the catalog)
     * only by how it is written.
     */
    fun sameCondition(a: Condition?, b: Condition?): Boolean {
        if (a == null || b == null) {
            return a == null && b == null
        }
        val x = check(a)
        val y = check(b)
        return if (x != null && y != null) x == y else written(a) == written(b)
    }

    internal companion object {
        fun same(a: Binding, b: Binding): Boolean =
            a.id == b.id && a.subject == b.subject && a.policy == b.policy && a.scope.app == b.scope.app &&
                a.scope.space == b.scope.space && a.expiresAt == b.expiresAt && a.breakGlass == b.breakGlass

        /** A rendering of [condition] as written: equal renderings are the same condition. */
        private fun written(condition: Condition): String = when (condition) {
            is Condition.And -> condition.conditions.joinToString(",", "and(", ")", transform = ::written)
            is Condition.Or -> condition.conditions.joinToString(",", "or(", ")", transform = ::written)
            is Condition.Not -> "not(${written(condition.condition)})"
            is Condition.Eq -> "eq(${condition.attribute},${operand(condition.value)},${condition.ignoreCase})"
            is Condition.In -> "in(${condition.attribute},${condition.values.map(::operand)})"
            is Condition.Contains -> "contains(${operand(condition.value)},${condition.attribute})"
            is Condition.Prefix -> "prefix(${condition.attribute},${operand(condition.value)},${condition.ignoreCase})"
            is Condition.Cidr -> "cidr(${condition.attribute},${condition.blocks.map(::operand)})"
            is Condition.Exists -> "exists(${condition.attribute})"
            is Condition.Time -> with(condition) { "time($notBefore,$notAfter,$zone,$daysOfWeek,$from,$to)" }
        }

        private fun operand(operand: Operand): String = when (operand) {
            is Operand.Attr -> "attr(${operand.path})"
            is Literal.Str -> "str(${operand.value})"
            is Literal.Bool -> "bool(${operand.value})"
        }
    }
}
