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
 * The index keys of a condition for one attribute (RFC 0005 §4.1): an over-approximation of the values the attribute
 * can have when the condition is true. An index that files a statement under each of its keys, and under "any value"
 * when it is [Unconstrained], never misses a statement that can match (property A1).
 */
sealed class IndexKeys {
    /** Whether a request whose attribute has [value] (null when absent) can satisfy the condition. */
    abstract fun admits(value: String?): Boolean

    /** ⊤: the condition does not constrain the attribute. */
    object Unconstrained : IndexKeys() {
        override fun admits(value: String?): Boolean = true

        override fun toString(): String = "⊤"
    }

    /**
     * The condition is true only if the attribute is present and its value is in [values], or, when [folded], its
     * ASCII-folded value is. Empty when the condition cannot be true: the statement belongs in no bucket.
     */
    class Only internal constructor(val values: Set<String>, val folded: Boolean) : IndexKeys() {
        override fun admits(value: String?): Boolean =
            value != null && (if (folded) Ascii.lowercase(value) else value) in values

        override fun equals(other: Any?): Boolean = other is Only && other.values == values && other.folded == folded

        override fun hashCode(): Int = 31 * values.hashCode() + folded.hashCode()

        override fun toString(): String = (if (folded) "folded" else "") + values.sortedWith { a, b -> a.compareTo(b) }
    }

    companion object {
        /**
         * The keys of [condition] for the [AttributeType.STRING] attribute [attribute].
         *
         * Only `eq` with a literal and `in` of literals constrain; `not`, `prefix`, attribute comparisons and every
         * other operator are ⊤. `and` intersects the constraints of its operands, `or` unites them and is ⊤ if any
         * operand is.
         */
        fun of(condition: CheckedCondition, attribute: Attribute): IndexKeys {
            require(attribute.type == AttributeType.STRING) { "Only String attributes have index keys: $attribute" }
            return keys(condition, attribute)
        }

        private fun keys(condition: CheckedCondition, attribute: Attribute): IndexKeys = when (condition) {
            is CheckedCondition.Eq -> if (condition.attribute == attribute) {
                Only(setOf(condition.value), condition.ignoreCase)
            } else {
                Unconstrained
            }

            is CheckedCondition.In -> if (condition.attribute == attribute) {
                Only(condition.values, folded = false)
            } else {
                Unconstrained
            }

            is CheckedCondition.And -> intersect(condition.conditions.map { keys(it, attribute) })
            is CheckedCondition.Or -> unite(condition.conditions.map { keys(it, attribute) })
            else -> Unconstrained
        }

        /**
         * Every operand's constraint holds. Exact constraints stay exact, filtered by the folded ones: a value in
         * every exact set whose folded form is in every folded set. Without exact constraints the result is folded.
         */
        private fun intersect(operands: List<IndexKeys>): IndexKeys {
            val constraints = operands.filterIsInstance<Only>()
            if (constraints.isEmpty()) {
                return Unconstrained
            }
            val exact = constraints.filterNot { it.folded }
            val folded = constraints.filter { it.folded }
            val base = if (exact.isEmpty()) folded else exact
            var values: Set<String> = base.first().values
            for (constraint in base) {
                values = values.filterTo(HashSet()) { it in constraint.values }
            }
            if (exact.isNotEmpty()) {
                for (constraint in folded) {
                    values = values.filterTo(HashSet()) { Ascii.lowercase(it) in constraint.values }
                }
            }
            return Only(values, folded = exact.isEmpty())
        }

        /** One operand's constraint holds. Mixing exact and folded constraints folds them all. */
        private fun unite(operands: List<IndexKeys>): IndexKeys {
            val constraints = operands.filterIsInstance<Only>()
            if (constraints.size != operands.size) {
                return Unconstrained
            }
            val folded = constraints.any { it.folded }
            val values = HashSet<String>()
            for (constraint in constraints) {
                if (folded && !constraint.folded) {
                    constraint.values.mapTo(values) { Ascii.lowercase(it) }
                } else {
                    values.addAll(constraint.values)
                }
            }
            return Only(values, folded)
        }
    }
}
