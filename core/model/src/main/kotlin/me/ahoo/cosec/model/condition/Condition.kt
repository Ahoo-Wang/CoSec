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
 * A condition as written (RFC 0005 §3.1), before it is checked: attribute paths and literals are as the document
 * has them. The document parser in the host builds it; [ConditionChecker] turns it into a [CheckedCondition] or a
 * list of errors.
 *
 * ```jsonc
 * { "and": [
 *     { "eq": ["subject.authenticated", true] },
 *     { "in": ["scope.space", ["team-a", "team-b"]] },
 *     { "not": { "cidr": ["environment.ip", ["10.0.0.0/8"]] } },
 *     { "eq": ["resource.var.ownerId", { "attr": "subject.id" }] }
 * ] }
 * ```
 *
 * The operator set is fixed. There is no `true` or `false` condition: a statement without a condition always
 * applies, and one that never does is useless.
 */
sealed interface Condition {
    /** `{"eq": [attribute, value], "ignoreCase": false}`. */
    class Eq(val attribute: String, val value: Operand, val ignoreCase: Boolean = false) : Condition

    /** `{"in": [attribute, [values...]]}`: the attribute's value is one of [values]. */
    class In(val attribute: String, val values: List<Literal>) : Condition

    /** `{"in": [value, attribute]}`: the set [attribute] contains [value]; an absent set is empty. */
    class Contains(val value: Literal, val attribute: String) : Condition

    /** `{"prefix": [attribute, value], "ignoreCase": false}`. On a labels attribute, a prefix of whole labels. */
    class Prefix(val attribute: String, val value: Literal, val ignoreCase: Boolean = false) : Condition

    /** `{"cidr": [attribute, [blocks...]]}`. */
    class Cidr(val attribute: String, val blocks: List<Literal>) : Condition

    /** `{"time": {...}}`: the configuration of the 7.x `time` condition, read against `environment.time`. */
    class Time(
        val notBefore: String? = null,
        val notAfter: String? = null,
        val zone: String? = null,
        val daysOfWeek: List<String>? = null,
        val from: String? = null,
        val to: String? = null,
    ) : Condition

    /** `{"exists": attribute}`. */
    class Exists(val attribute: String) : Condition

    /** `{"and": [conditions...]}`: at least one, evaluated left to right, short-circuiting. */
    class And(val conditions: List<Condition>) : Condition

    /** `{"or": [conditions...]}`: at least one, evaluated left to right, short-circuiting. */
    class Or(val conditions: List<Condition>) : Condition

    /** `{"not": condition}`. */
    class Not(val condition: Condition) : Condition
}

/** The right-hand side of `eq`: a literal, or `{"attr": path}`. */
sealed interface Operand {
    class Attr(val path: String) : Operand
}

/** A JSON literal. Its type is its JSON type: a string never stands for a boolean (RFC 0005 §3.2). */
sealed interface Literal : Operand {
    data class Str(val value: String) : Literal

    data class Bool(val value: Boolean) : Literal
}
