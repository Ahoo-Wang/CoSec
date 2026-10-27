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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import tools.jackson.databind.JsonNode

/** A translated condition: one that always holds, one that never does, or an AST. The AST has neither constant. */
internal sealed interface Translated {
    data object Always : Translated

    data object Never : Translated

    class Ast(val condition: Condition) : Translated

    companion object {
        fun and(items: List<Translated>): Translated {
            if (items.any { it == Never }) return Never
            val conditions = items.filterIsInstance<Ast>().map { it.condition }
            return when (conditions.size) {
                0 -> Always
                1 -> Ast(conditions.single())
                else -> Ast(Condition.And(conditions))
            }
        }

        fun or(items: List<Translated>): Translated {
            if (items.any { it == Always }) return Always
            val conditions = items.filterIsInstance<Ast>().map { it.condition }
            return when (conditions.size) {
                0 -> Never
                1 -> Ast(conditions.single())
                else -> Ast(Condition.Or(conditions))
            }
        }

        fun not(item: Translated): Translated = when (item) {
            Always -> Never
            Never -> Always
            is Ast -> Ast((item.condition as? Condition.Not)?.condition ?: Condition.Not(item.condition))
        }
    }
}

/**
 * Translates 7.x conditions into the condition AST (RFC 0005 §7.1), exactly where the AST can say the same, and
 * otherwise only in the safe direction:
 *
 * - A condition is read in a direction: [translate]'s `deny` is true when its truth leads toward refusing (a DENY
 *   statement, a rate limit rule), and each `negate` flips it. A translation that matches fewer requests than 7.x
 *   (narrower) is safe only in the ALLOW direction; one that matches more (wider) only in the DENY direction.
 *   Anything else is rejected with its location: a translation never lets through a request 7.x refused.
 * - 7.x reads an absent part as `""` (and the anonymous principal's id as `(0)`), the AST as absent. A predicate is
 *   evaluated on that 7.x value here, and when it holds, `not(exists)` is added: absence translates exactly.
 * - Every intended difference (RFC 0005 §8 D1–D3) and every narrowing is recorded with its location.
 */
internal class ConditionTranslator(
    private val reject: (RejectionKind, String, String) -> Unit,
    private val record: (SemanticChange) -> Unit,
) {
    /** The translation of [node] at [location] read in the DENY direction when [deny]; null when it is rejected. */
    fun translate(node: JsonNode?, location: String, deny: Boolean): Translated? {
        if (node == null || node.isNull) {
            return Translated.Always
        }
        if (!node.isObject || node.size() != 1) {
            return rejected(RejectionKind.UNDECODABLE, location, "a condition is one typed object")
        }
        val (type, config) = node.properties().first().let { it.key to it.value }
        val at = "$location.$type"
        val negated = config.isObject && config.get(NEGATE)?.asBoolean() == true
        // Under negate, the inner condition's truth leads the other way.
        val inner = translateType(type, config, at, deny != negated) ?: return null
        return if (negated) Translated.not(inner) else inner
    }

    private fun translateType(type: String, config: JsonNode, location: String, deny: Boolean): Translated? = when (type) {
        "all" -> Translated.Always
        "authenticated" -> ast(Condition.Eq(SUBJECT_AUTHENTICATED, Literal.Bool(true)))
        "inTenant" -> inTenant(config, location)
        "inRole" -> inRole(config, location, deny)
        "bool" -> bool(config, location, deny)
        "time" -> TimeTranslation.translate(config, location, reject)
        in PART_TYPES -> PartTranslation(this, config, location, deny).translate(type)
        "path" -> rejected(
            RejectionKind.CONDITION_NOT_TRANSLATED,
            location,
            "a path condition translates only on request.path, as a whole or top-level `and` member of a statement " +
                "condition whose action is `all`: it becomes the statement's resource",
        )

        in MatcherTranslator.EXPRESSION_TYPES -> rejected(
            RejectionKind.EXPRESSION_CONDITION,
            location,
            "a [$type] expression: rewrite it as conditions of the 8.0 policy language",
        )

        in MatcherTranslator.BUILTIN_CONDITION_TYPES -> rejected(
            RejectionKind.CONDITION_NOT_TRANSLATED,
            location,
            "a [$type] condition has no exact translation (RFC 0005 §7.1)",
        )

        else -> rejected(
            RejectionKind.CUSTOM_CONDITION,
            location,
            "condition type [$type] is a custom ConditionMatcherFactory"
        )
    }

    /** `and` all hold, and when `or` is not empty one of it does (7.x `BoolConditionMatcher`). */
    private fun bool(config: JsonNode, location: String, deny: Boolean): Translated? {
        if (!config.isObject) {
            return rejected(RejectionKind.UNDECODABLE, location, "a bool condition is an object")
        }
        fun members(key: String) = config.get(key)?.values()?.mapIndexed { i, member ->
            translate(member, "$location.$key[$i]", deny)
        }.orEmpty()
        val and = members(AND)
        val or = members(OR)
        if (and.any { it == null } || or.any { it == null }) {
            return null
        }
        val all = and.filterNotNull() + if (or.isEmpty()) emptyList() else listOf(Translated.or(or.filterNotNull()))
        return Translated.and(all)
    }

    /** RFC 0005 §7.1: the tenant of the principal, as 7.x's `Tenant` classifies it. */
    private fun inTenant(config: JsonNode, location: String): Translated? {
        val value = config.get(VALUE)?.takeIf { it.isString }?.asString()?.uppercase()
        return when (value) {
            "DEFAULT" -> ast(Condition.Eq(SUBJECT_TENANT, Literal.Str(DEFAULT_TENANT)))
            "PLATFORM" -> ast(Condition.Eq(SUBJECT_TENANT, Literal.Str(PLATFORM_TENANT)))
            "USER" -> ast(
                Condition.Not(
                    Condition.In(SUBJECT_TENANT, listOf(Literal.Str(DEFAULT_TENANT), Literal.Str(PLATFORM_TENANT)))
                )
            )
            else -> rejected(RejectionKind.UNDECODABLE, location, "inTenant is default, user or platform")
        }
    }

    /**
     * 7.x `inRole`: the principal holds the role as written, or a bare role holds `role@<requested space>`. RFC 0005
     * §7.1 translates it by direction, so a DENY never narrows and an ALLOW never widens (D1).
     */
    private fun inRole(config: JsonNode, location: String, deny: Boolean): Translated? {
        val value = config.get(VALUE)?.takeIf { it.isString }?.asString()
            ?: return rejected(RejectionKind.UNDECODABLE, location, "inRole needs a role")
        val separator = value.indexOf(ROLE_SEPARATOR)
        val role = if (separator < 0) value else value.substring(0, separator)
        val space = if (separator < 0) null else value.substring(separator + 1)
        if (role.isEmpty() || space?.isEmpty() == true || space?.contains(ROLE_SEPARATOR) == true) {
            return rejected(
                RejectionKind.CONDITION_NOT_TRANSLATED,
                location,
                "inRole [$value] names no role in a space"
            )
        }
        return when {
            // Literal in 7.x: holding `x@s` matches in every requested space; the scoped roles say the same.
            space != null && deny -> ast(Condition.Contains(Literal.Str(value), SUBJECT_SCOPED_ROLES))
            space != null -> {
                d1(location, value, "ALLOW", "matches only while the request is in space [$space]")
                ast(
                    Condition.And(
                        listOf(
                            Condition.Eq(SCOPE_SPACE, Literal.Str(space)),
                            Condition.Contains(Literal.Str(role), SCOPE_ROLES)
                        )
                    )
                )
            }

            deny -> {
                d1(location, value, "DENY", "matches the role held in any space")
                ast(Condition.Contains(Literal.Str(role), SUBJECT_ROLES))
            }

            else -> {
                d1(location, value, "ALLOW", "matches only a role in effect in the requested space")
                ast(Condition.Contains(Literal.Str(role), SCOPE_ROLES))
            }
        }
    }

    private fun d1(location: String, value: String, direction: String, effect: String) {
        record(SemanticChange("D1", location, "inRole [$value] in the $direction direction: 8.0 $effect"))
    }

    fun note(change: String, location: String, shape: String) {
        record(SemanticChange(change, location, shape))
    }

    fun <T> rejected(kind: RejectionKind, location: String, message: String): T? {
        reject(kind, location, message)
        return null
    }

    private fun ast(condition: Condition): Translated = Translated.Ast(condition)

    companion object {
        const val NEGATE = "negate"
        const val VALUE = "value"
        const val AND = "and"
        const val OR = "or"
        const val ROLE_SEPARATOR = '@'
        const val DEFAULT_TENANT = "(0)"
        const val PLATFORM_TENANT = "(platform)"
        const val SUBJECT_AUTHENTICATED = "subject.authenticated"
        const val SUBJECT_TENANT = "subject.tenant"
        const val SUBJECT_ROLES = "subject.roles"
        const val SUBJECT_SCOPED_ROLES = "subject.scopedRoles"
        const val SCOPE_SPACE = "scope.space"
        const val SCOPE_ROLES = "scope.roles"
        val PART_TYPES = setOf("eq", "in", "startsWith", "endsWith", "regular", "contains")

        /** An operand of `eq`: an attribute read on the right-hand side. */
        fun attr(path: String): Operand = Operand.Attr(path)
    }
}
