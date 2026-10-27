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

import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

/**
 * Translates 7.x statements and the policy (or app) condition over them into 8.0 statements (RFC 0005 §5.2, §7):
 * the action by [MatcherTranslator], conditions by [ConditionTranslator], each in the direction of its statement.
 */
internal class StatementTranslator(
    private val actions: MatcherTranslator,
    private val conditions: ConditionTranslator,
    private val reject: (RejectionKind, String, String) -> Unit,
) {
    /**
     * A 7.x statement (or permission) at [location] as an 8.0 statement without its policy condition, or null when a
     * part of it is rejected. Every part is translated even after one is rejected, so one run reports them all.
     */
    fun statement(node: JsonNode, location: String, name: String, direction: Boolean? = null): StatementSource? {
        // A rate limit rule has no effect; its condition limits, so it reads in the DENY direction ([direction]).
        val deny = direction ?: (node.get(EFFECT)?.takeIf { it.isString }?.asString()?.equals("deny", ignoreCase = true) == true)
        val actionNode = node.get(ACTION)
        if (actionNode == null || actionNode.isNull) {
            reject(RejectionKind.UNDECODABLE, location, "a statement needs an action")
            return null
        }
        val action = actions.action(actionNode, "$location.$ACTION")
        val conditionNode = node.get(CONDITION)
        // A path condition on request.path is the resource when the action names none (RFC 0005 §7.1). A 7.x path
        // condition captures nothing, so where the rest reads path variables (`""` in 7.x) a resource that captured
        // them would match more: it stays a condition, and is rejected.
        val path = if (action != null && action.resources.isEmpty()) {
            PathCondition.extract(conditionNode)?.takeUnless { it.rest?.toString()?.contains(PATH_VARIABLES) == true }
        } else {
            null
        }
        val resources = path?.resources?.let { actions.pathResources(it, "$location.$CONDITION") }
        val condition = conditions.translate(if (path != null) path.rest else conditionNode, "$location.$CONDITION", deny)
        if (action == null || condition == null || (path != null && resources == null)) {
            return null
        }
        if (condition == Translated.Never) {
            reject(RejectionKind.CONDITION_NOT_TRANSLATED, "$location.$CONDITION", "the condition can never hold: remove the statement")
            return null
        }
        return StatementSource(
            name = name,
            effect = if (deny) Effect.DENY else Effect.ALLOW,
            actions = action.actions,
            resources = resources ?: action.resources,
            condition = (condition as? Translated.Ast)?.condition,
        )
    }

    /**
     * The policy condition at [location] over [statements]: translated in the direction of each statement. When the
     * DENY and the ALLOW readings differ, it cannot be shared, so it moves into each statement (RFC 0005 §7.1,
     * "direction"); the result is the same, since a policy condition is a conjunct of every statement. Null when
     * rejected.
     */
    fun policy(node: JsonNode?, location: String, statements: List<StatementSource>): Pair<Condition?, List<StatementSource>>? {
        val directions = statements.map { it.effect == Effect.DENY }.distinct().ifEmpty { listOf(false) }
        val translated = directions.associateWith { conditions.translate(node, location, it) }
        if (translated.values.any { it == null }) {
            return null
        }
        val byDirection = translated.mapValues { it.value!! }
        if (byDirection.values.any { it == Translated.Never }) {
            reject(RejectionKind.CONDITION_NOT_TRANSLATED, location, "the condition can never hold: remove the policy")
            return null
        }
        val shared = byDirection.values.map { render((it as? Translated.Ast)?.condition) }.distinct().size == 1
        if (shared) {
            return (byDirection.values.first() as? Translated.Ast)?.condition to statements
        }
        return null to statements.map { statement ->
            val guard = byDirection.getValue(statement.effect == Effect.DENY)
            val condition = Translated.and(listOfNotNull(guard, statement.condition?.let { Translated.Ast(it) }))
            StatementSource(statement.name, statement.effect, statement.actions, statement.resources, (condition as? Translated.Ast)?.condition)
        }
    }

    /** A 7.x `path` condition on request.path, taken out of a statement's condition, and what is left of it. */
    class PathCondition(val resources: JsonNode, val rest: JsonNode?) {
        companion object {
            private val factory = JsonNodeFactory.instance

            /** The path condition, when [node] is one or an `and` holding exactly one, neither negated. */
            fun extract(node: JsonNode?): PathCondition? {
                if (node == null || !node.isObject || node.size() != 1) {
                    return null
                }
                val (type, config) = node.properties().first().let { it.key to it.value }
                if (type == PATH && isRequestPath(config)) {
                    return PathCondition(config, null)
                }
                if (type != BOOL || negated(config) || config.get(OR)?.isEmpty == false) {
                    return null
                }
                val members = config.get(AND)?.values()?.toList().orEmpty()
                val paths = members.filter { member ->
                    member.isObject && member.size() == 1 && member.get(PATH)?.let(::isRequestPath) == true
                }
                if (paths.size != 1) {
                    return null
                }
                val rest = factory.objectNode().also { bool ->
                    bool.putObject(BOOL).putArray(AND).addAll(members.filterNot { it === paths.single() })
                }
                return PathCondition(paths.single().get(PATH), rest)
            }

            private fun isRequestPath(config: JsonNode): Boolean =
                config.isObject && !negated(config) && config.get("part")?.asString() == "request.path"

            private fun negated(config: JsonNode): Boolean = config.isObject && config.get("negate")?.asBoolean() == true
        }
    }

    companion object {
        const val EFFECT = "effect"
        const val ACTION = "action"
        const val CONDITION = "condition"
        private const val PATH = "path"
        private const val PATH_VARIABLES = "request.path.var."
        private const val BOOL = "bool"
        private const val AND = "and"
        private const val OR = "or"

        /** A canonical text of a condition, to compare two translations. */
        fun render(condition: Condition?): String = when (condition) {
            null -> "-"
            is Condition.Eq -> "eq(${condition.attribute},${operand(condition.value)},${condition.ignoreCase})"
            is Condition.In -> "in(${condition.attribute},${condition.values.map(::operand)})"
            is Condition.Contains -> "contains(${operand(condition.value)},${condition.attribute})"
            is Condition.Prefix -> "prefix(${condition.attribute},${operand(condition.value)},${condition.ignoreCase})"
            is Condition.Cidr -> "cidr(${condition.attribute},${condition.blocks.map(::operand)})"
            is Condition.Time -> with(condition) { "time($notBefore,$notAfter,$zone,$daysOfWeek,$from,$to)" }
            is Condition.Exists -> "exists(${condition.attribute})"
            is Condition.And -> condition.conditions.joinToString(",", "and(", ")", transform = ::render)
            is Condition.Or -> condition.conditions.joinToString(",", "or(", ")", transform = ::render)
            is Condition.Not -> "not(${render(condition.condition)})"
        }

        private fun operand(operand: Operand): String = when (operand) {
            is Operand.Attr -> "attr:${operand.path}"
            is Literal.Str -> "s:${operand.value.length}:${operand.value}"
            is Literal.Bool -> "b:${operand.value}"
        }
    }
}
