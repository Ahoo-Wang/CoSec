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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers

/**
 * Conditions that type-check and still cannot mean what their author wrote. [ConditionChecker] accepts them; the
 * compiler refuses them where a miss would let a request through, and the control plane warns where it only narrows.
 *
 * - `DOC-9`: `ignoreCase` on a literal with a letter beyond ASCII. The fold is ASCII only (RFC 0005 §3.2), so `Ärzte`
 *   does not match `ärzte`: the atom matches fewer values than it reads.
 * - `DOC-11`: a `subject.id` literal beginning with `(`. The anonymous principal has no `subject.id` in a document, and
 *   no signed-in principal has a reserved id (`IDN-12`), so the atom never holds, and under a `not` it holds for every
 *   anonymous request.
 *
 * **Direction.** An atom that matches fewer values makes the condition match fewer requests where it stands under an
 * even number of `not`s, and more under an odd number (`and` and `or` keep the direction). Whoever owns the condition
 * says which of the two lets a request through: [check]'s `fewerAdmits` and `moreAdmits`. A DENY statement or a rate
 * limit rule: fewer. An ALLOW statement: more. A policy condition guards every statement of its policy: fewer when the
 * policy has a DENY, more when it has an ALLOW, both when it has both.
 *
 * Run it on a condition [ConditionChecker] has accepted: the checker bounds its depth and size.
 */
@InternalCoSecApi
object ConditionLints {
    /** What is wrong at [path] (relative to the condition, `$` being the condition), and whether it [refuses] compiling. */
    @InternalCoSecApi
    class Finding(val path: String, val message: String, val refuses: Boolean)

    /** The findings of [condition], in document order. */
    fun check(condition: Condition, fewerAdmits: Boolean, moreAdmits: Boolean): List<Finding> {
        val findings = ArrayList<Finding>()
        walk(condition, ROOT, negated = false, fewerAdmits, moreAdmits, findings)
        return findings
    }

    private fun walk(
        condition: Condition,
        path: String,
        negated: Boolean,
        fewerAdmits: Boolean,
        moreAdmits: Boolean,
        findings: MutableList<Finding>,
    ) {
        when (condition) {
            is Condition.And -> condition.conditions.forEachIndexed { i, c ->
                walk(c, "$path.and[$i]", negated, fewerAdmits, moreAdmits, findings)
            }

            is Condition.Or -> condition.conditions.forEachIndexed { i, c ->
                walk(c, "$path.or[$i]", negated, fewerAdmits, moreAdmits, findings)
            }

            is Condition.Not -> walk(condition.condition, "$path.not", !negated, fewerAdmits, moreAdmits, findings)
            is Condition.Eq -> (condition.value as? Literal.Str)?.let {
                atom(
                    Atom("eq", condition.attribute, it.value, condition.ignoreCase),
                    path,
                    admits(negated, fewerAdmits, moreAdmits),
                    findings
                )
            }

            is Condition.Prefix -> (condition.value as? Literal.Str)?.let {
                atom(
                    Atom("prefix", condition.attribute, it.value, condition.ignoreCase),
                    path,
                    admits(negated, fewerAdmits, moreAdmits),
                    findings
                )
            }

            is Condition.In -> condition.values.forEachIndexed { i, value ->
                (value as? Literal.Str)?.let { reservedSubject(condition.attribute, it.value, "$path.in[1][$i]", "in") }
                    ?.let { findings += it }
            }

            else -> Unit
        }
    }

    /** Whether an atom that matches fewer values lets a request through where it stands. */
    private fun admits(negated: Boolean, fewerAdmits: Boolean, moreAdmits: Boolean): Boolean =
        if (negated) moreAdmits else fewerAdmits

    /** An `eq` or `prefix` of [attribute] with a string literal [value]. */
    private class Atom(val operator: String, val attribute: String, val value: String, val ignoreCase: Boolean)

    private fun atom(atom: Atom, path: String, admits: Boolean, findings: MutableList<Finding>) {
        val at = "$path.${atom.operator}[1]"
        reservedSubject(atom.attribute, atom.value, at, atom.operator)?.let { findings += it }
        if (atom.ignoreCase) {
            fold(atom.operator, atom.value, at, admits)?.let { findings += it }
        }
    }

    private fun fold(operator: String, value: String, path: String, admits: Boolean): Finding? {
        if (!foldsBeyondAscii(value)) {
            return null
        }
        return if (admits) {
            Finding(path, foldMessage(operator, value), refuses = true)
        } else {
            val message = "ignoreCase folds ASCII letters only, and [$value] holds letters beyond ASCII, so this $operator " +
                "does not match [${otherSpelling(value)}]: here that only narrows what the statement grants (DOC-9)"
            Finding(path, message, refuses = false)
        }
    }

    private fun reservedSubject(attribute: String, value: String, path: String, operator: String): Finding? {
        if (attribute != Attribute.Kind.SUBJECT_ID.path || !Identifiers.isReserved(value)) {
            return null
        }
        val message = "subject.id is absent for the anonymous principal, and no signed-in principal has an id beginning " +
            "with [${Identifiers.RESERVED_PREFIX}] (IDN-12): this $operator on [$value] never holds, and under a not it " +
            "holds for every anonymous request. Write {\"eq\": [\"subject.authenticated\", false]} for anonymous " +
            "requests, true for signed-in ones (DOC-11)"
        return Finding(path, message, refuses = true)
    }

    /**
     * Why `ignoreCase` on [value] in the direction where a miss lets a request through does not compile (`DOC-9`): the
     * message the compiler and `cosec-migrate` both give.
     */
    fun foldMessage(operator: String, value: String): String {
        val instead = if (operator == "prefix") {
            "write an or of one prefix per spelling, without ignoreCase"
        } else {
            "list every spelling in an in, without ignoreCase"
        }
        return "ignoreCase folds ASCII letters only, and [$value] holds letters beyond ASCII, so this $operator does not " +
            "match [${otherSpelling(value)}]: here that lets through a request it was written to stop; $instead (DOC-9)"
    }

    /**
     * Why `resource.attributes.<name>` does not resolve in a document (`DOC-10`), and where facts about a resource go
     * instead. The attribute kind stays in the model for a later producer; [ConditionChecker.attribute] refuses it.
     */
    fun unsetResourceAttribute(path: String): String = unset(path)

    /** [unsetResourceAttribute] when [parsed] is a resource attribute, else null: the checker's `unresolved` asks. */
    fun unsetResourceAttribute(parsed: Attribute?, path: String): String? =
        unset(path).takeIf { parsed?.kind == Attribute.Kind.RESOURCE_ATTRIBUTE }

    private fun unset(path: String): String =
        "`$path` is never set: no request, check, simulate or explain gives a resource attributes, so a DENY on it " +
            "would deny nothing and a not on it would match every request. Put facts about a resource in its id and " +
            "read them with a pattern variable, resource.var.<name> (Object-level rules, integration/checks.md) (DOC-10)"

    /** Whether [value] holds a character beyond ASCII that has another case, which `ignoreCase` does not fold. */
    fun foldsBeyondAscii(value: String): Boolean = codePoints(value).any { it >= ASCII_END && hasOtherCase(it) }

    /** Whether [value] holds any character that has another case, ASCII letters included. */
    fun hasCaseMapping(value: String): Boolean = codePoints(value).any(::hasOtherCase)

    /** [value] with the case of each character beyond ASCII swapped: a spelling the ASCII fold does not reach. */
    fun otherSpelling(value: String): String {
        val builder = StringBuilder(value.length)
        for (c in codePoints(value)) {
            val upper = Character.toUpperCase(c)
            builder.appendCodePoint(
                when {
                    c < ASCII_END -> c
                    upper != c -> upper
                    else -> Character.toLowerCase(c)
                },
            )
        }
        return builder.toString()
    }

    private fun hasOtherCase(c: Int): Boolean =
        Character.toUpperCase(c) != c || Character.toLowerCase(c) != c || Character.toTitleCase(c) != c

    private fun codePoints(value: String): List<Int> {
        val points = ArrayList<Int>(value.length)
        var i = 0
        while (i < value.length) {
            val c = Character.codePointAt(value, i)
            points += c
            i += Character.charCount(c)
        }
        return points
    }

    private const val ROOT = "$"
    private const val ASCII_END = 0x80
}
