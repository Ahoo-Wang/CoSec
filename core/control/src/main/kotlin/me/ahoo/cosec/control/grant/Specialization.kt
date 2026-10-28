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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.CheckResult
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand

/** The value of an attribute that a [Specialization] substitutes; [value] is `null` when the attribute is absent. */
internal sealed interface Constant {
    data class Str(val value: String?) : Constant

    data class Bool(val value: Boolean?) : Constant

    data class Strings(val value: Set<String>?) : Constant
}

/** What a condition folds to once some of its attributes are constants. */
internal sealed interface Folded {
    object True : Folded

    object False : Folded

    class Residual(val condition: Condition) : Folded

    /** The condition cannot be compared: it reads an attribute it cannot be specialized for. */
    class Incomparable(val reason: String) : Folded
}

/**
 * Substitutes constants for attributes and folds the result (RFC 0007 §7.4.3 `specialize`): an atom that reads only
 * constants is evaluated, an `eq` between a constant and another attribute becomes an `eq` with a literal, `and`,
 * `or` and `not` fold. Atoms evaluate as the data plane does, through [ConditionChecker], so an absent constant is
 * false in every atom (RFC 0005 §3.4).
 *
 * - [renames] renames `resource.var.<name>` to the variable of another pattern that captures the same value; a
 *   variable without a rename is [Folded.Incomparable] when [renames] is given.
 * - [opaque] attributes may not remain in the result: they depend on who is asking, and no constant is known for
 *   them (`scope.roles` over several spaces).
 * - an attribute outside the catalog (an external attribute, `ext.*`) is incomparable: it may depend on the subject.
 * - `exists` over a substituted set is incomparable: whether an empty set is absent is the host's to say.
 */
internal class Specialization(
    private val checker: ConditionChecker,
    private val constants: (Attribute) -> Constant?,
    private val opaque: (Attribute) -> Boolean = { false },
    private val renames: Map<String, String>? = null,
) {
    private val input = ConstantInput(constants)

    fun fold(condition: Condition): Folded = when (condition) {
        is Condition.And -> group(condition.conditions, isAnd = true)
        is Condition.Or -> group(condition.conditions, isAnd = false)
        is Condition.Not -> when (val folded = fold(condition.condition)) {
            Folded.True -> Folded.False
            Folded.False -> Folded.True
            is Folded.Residual -> Folded.Residual(Condition.Not(folded.condition))
            is Folded.Incomparable -> folded
        }

        is Condition.Eq -> eq(condition)
        is Condition.Time -> Folded.Residual(condition)
        else -> atom(condition, reads(condition))
    }

    private fun group(conditions: List<Condition>, isAnd: Boolean): Folded {
        val remaining = ArrayList<Condition>()
        for (condition in conditions) {
            when (val folded = fold(condition)) {
                Folded.True -> if (!isAnd) return Folded.True
                Folded.False -> if (isAnd) return Folded.False
                is Folded.Residual -> remaining += folded.condition
                is Folded.Incomparable -> return folded
            }
        }
        return when {
            remaining.isEmpty() -> if (isAnd) Folded.True else Folded.False
            remaining.size == 1 -> Folded.Residual(remaining.single())
            isAnd -> Folded.Residual(Condition.And(remaining))
            else -> Folded.Residual(Condition.Or(remaining))
        }
    }

    private fun eq(condition: Condition.Eq): Folded {
        val value = condition.value
        if (value !is Operand.Attr) {
            return atom(condition, condition.attribute)
        }
        val left = parse(condition.attribute) ?: return outside(condition.attribute)
        val right = parse(value.path) ?: return outside(value.path)
        val leftConstant = constants(left)
        val rightConstant = constants(right)
        return when {
            leftConstant != null && rightConstant != null -> evaluate(condition)
            leftConstant != null -> compare(right, leftConstant, condition.ignoreCase)
            rightConstant != null -> compare(left, rightConstant, condition.ignoreCase)
            else -> residual(condition, left, right)
        }
    }

    /** `eq(attribute, constant)`. An absent constant equals nothing. */
    private fun compare(attribute: Attribute, constant: Constant, ignoreCase: Boolean): Folded {
        val literal = when (constant) {
            is Constant.Str -> constant.value?.let { Literal.Str(it) }
            is Constant.Bool -> constant.value?.let { Literal.Bool(it) }
            is Constant.Strings -> return Folded.Incomparable("a set is compared with ${attribute.path}")
        } ?: return Folded.False
        return residual(Condition.Eq(attribute.path, literal, ignoreCase), attribute)
    }

    private fun atom(condition: Condition, path: String): Folded {
        val attribute = parse(path) ?: return outside(path)
        val constant = constants(attribute) ?: return residual(condition, attribute)
        if (condition is Condition.Exists && constant is Constant.Strings) {
            return Folded.Incomparable("exists over ${attribute.path}")
        }
        return evaluate(condition)
    }

    private fun evaluate(condition: Condition): Folded = when (val checked = checker.check(condition)) {
        is CheckResult.Valid -> if (checked.condition.evaluate(input)) Folded.True else Folded.False
        is CheckResult.Invalid -> Folded.Incomparable("${checked.errors}")
    }

    private fun residual(condition: Condition, vararg attributes: Attribute): Folded {
        var renamed = condition
        for (attribute in attributes) {
            if (opaque(attribute)) {
                return Folded.Incomparable("${attribute.path} depends on who asks")
            }
            if (attribute.kind == Kind.RESOURCE_VAR && renames != null) {
                val name = renames[attribute.name]
                    ?: return Folded.Incomparable("${attribute.path} is not captured at the same position")
                renamed = rename(renamed, attribute.path, Attribute.of(Kind.RESOURCE_VAR, name).path)
            }
        }
        return Folded.Residual(renamed)
    }

    private fun outside(path: String): Folded = Folded.Incomparable("$path is not in the attribute catalog")

    private fun parse(path: String): Attribute? = Attribute.parse(path)

    private fun reads(condition: Condition): String = when (condition) {
        is Condition.In -> condition.attribute
        is Condition.Contains -> condition.attribute
        is Condition.Prefix -> condition.attribute
        is Condition.Cidr -> condition.attribute
        is Condition.Exists -> condition.attribute
        else -> error("$condition is not an atom with one attribute")
    }

    /** [condition] with [from] replaced by [to]; an atom reads it at most on both sides of an `eq`. */
    private fun rename(condition: Condition, from: String, to: String): Condition {
        fun path(path: String) = if (path == from) to else path
        return when (condition) {
            is Condition.Eq -> {
                val value = condition.value
                Condition.Eq(
                    path(condition.attribute),
                    if (value is Operand.Attr) Operand.Attr(path(value.path)) else value,
                    condition.ignoreCase,
                )
            }

            is Condition.In -> Condition.In(path(condition.attribute), condition.values)
            is Condition.Contains -> Condition.Contains(condition.value, path(condition.attribute))
            is Condition.Prefix -> Condition.Prefix(path(condition.attribute), condition.value, condition.ignoreCase)
            is Condition.Cidr -> Condition.Cidr(path(condition.attribute), condition.blocks)
            is Condition.Exists -> Condition.Exists(path(condition.attribute))
            else -> condition
        }
    }

    /** Checks a folded residual; `null` if it does not check. */
    fun check(condition: Condition): CheckedCondition? = (checker.check(condition) as? CheckResult.Valid)?.condition
}

/** Reads the constants; every other attribute is absent. Only atoms that read constants alone are evaluated. */
private class ConstantInput(private val constants: (Attribute) -> Constant?) : ConditionInput {
    override fun string(attribute: Attribute): String? = (constants(attribute) as? Constant.Str)?.value

    override fun bool(attribute: Attribute): Boolean? = (constants(attribute) as? Constant.Bool)?.value

    override fun stringSet(attribute: Attribute): Set<String>? = (constants(attribute) as? Constant.Strings)?.value

    override fun ip(attribute: Attribute): IpAddress? = null

    override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME
}

/** The attributes that depend on who asks (RFC 0007 §7.4.3): the grantor's and a grantee's values differ. */
internal val SUBJECT_KINDS: Set<Kind> = setOf(
    Kind.SUBJECT_ID,
    Kind.SUBJECT_TENANT,
    Kind.SUBJECT_AUTHENTICATED,
    Kind.SUBJECT_ROLES,
    Kind.SUBJECT_SCOPED_ROLES,
    Kind.SUBJECT_ATTRIBUTE,
    Kind.SCOPE_ROLES,
)
