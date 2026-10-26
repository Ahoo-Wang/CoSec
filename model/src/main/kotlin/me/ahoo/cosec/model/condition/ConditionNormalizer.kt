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

package me.ahoo.cosec.model.condition

/**
 * The normal form of a checked condition (RFC 0005 §4.2 step 1): nested `and`s and `or`s are flattened, duplicates
 * removed and operands sorted by [CheckedCondition.canonical]; an `and` or `or` of one operand is that operand;
 * `not(not(x))` is `x`. Literals are already folded by the checker.
 *
 * Normalizing never changes what a condition decides (property A3) and never adds nodes, so a normalized condition
 * stays within the limits it was checked against. Two conditions that differ only in operand order or nesting have
 * the same normal form.
 */
object ConditionNormalizer {
    fun normalize(condition: CheckedCondition): CheckedCondition = when (condition) {
        is CheckedCondition.And -> group(condition.conditions, isAnd = true)
        is CheckedCondition.Or -> group(condition.conditions, isAnd = false)
        is CheckedCondition.Not -> {
            // Normalize first: unwrapping a one-operand group can expose a `not`.
            val inner = normalize(condition.condition)
            if (inner is CheckedCondition.Not) inner.condition else CheckedCondition.Not(inner)
        }

        else -> condition
    }

    private fun group(conditions: List<CheckedCondition>, isAnd: Boolean): CheckedCondition {
        val operands = LinkedHashSet<CheckedCondition>()
        for (condition in conditions) {
            val normal = normalize(condition)
            when {
                isAnd && normal is CheckedCondition.And -> operands.addAll(normal.conditions)
                !isAnd && normal is CheckedCondition.Or -> operands.addAll(normal.conditions)
                else -> operands.add(normal)
            }
        }
        val sorted = operands.sortedWith { a, b -> a.canonical.compareTo(b.canonical) }
        return when {
            sorted.size == 1 -> sorted.single()
            isAnd -> CheckedCondition.And(sorted)
            else -> CheckedCondition.Or(sorted)
        }
    }
}
