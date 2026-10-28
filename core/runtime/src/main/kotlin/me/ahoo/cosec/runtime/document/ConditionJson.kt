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

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The JSON syntax of a condition (RFC 0005 §3.1): an object with one operator, plus `ignoreCase` beside `eq` and
 * `prefix`. Reading checks only the syntax; types, the attribute catalog and the cost bounds are checked when the
 * statement compiles (`PolicyCompiler`).
 */
internal object ConditionJson {
    private const val IGNORE_CASE = "ignoreCase"
    private const val ATTR = "attr"
    private val TIME_FIELDS = arrayOf("notBefore", "notAfter", "zone", "daysOfWeek", "from", "to")
    private val factory = JsonNodeFactory.instance

    fun read(node: DocumentNode): Condition {
        val names = node.names()
        val operator = (names - IGNORE_CASE).singleOrNull()
            ?: node.fail("a condition has exactly one operator, found ${names.ifEmpty { listOf("none") }.joinToString()}")
        val ignoreCase = node.field(IGNORE_CASE)?.bool()
        if (ignoreCase != null && operator != "eq" && operator != "prefix") {
            node.fail("$IGNORE_CASE applies to eq and prefix only")
        }
        val operands = node.required(operator)
        return when (operator) {
            "eq", "prefix", "cidr", "in" -> binary(operator, operands, ignoreCase ?: false)
            "time" -> time(operands)
            "exists" -> Condition.Exists(operands.text())
            "and" -> Condition.And(operands.elements().map(::read).ifEmpty { operands.fail("and needs a condition") })
            "or" -> Condition.Or(operands.elements().map(::read).ifEmpty { operands.fail("or needs a condition") })
            "not" -> Condition.Not(read(operands))
            else -> node.fail("unknown operator [$operator]")
        }
    }

    /** The operators written `[left, right]`. */
    private fun binary(operator: String, operands: DocumentNode, ignoreCase: Boolean): Condition {
        val pair = operands.elements()
        if (pair.size != 2) {
            operands.fail("expected [left, right]")
        }
        val (left, right) = pair
        return when (operator) {
            "eq" -> Condition.Eq(left.text(), operand(right), ignoreCase)
            "prefix" -> Condition.Prefix(left.text(), string(right), ignoreCase)
            "cidr" -> Condition.Cidr(left.text(), right.elements().map(::string))
            // [attribute, [literals]] or [literal, set attribute]; a set holds strings only.
            else -> if (right.json.isArray) {
                Condition.In(left.text(), right.elements().map(::literal))
            } else {
                Condition.Contains(string(left), right.text())
            }
        }
    }

    private fun operand(node: DocumentNode): Operand =
        if (node.json.isObject) {
            node.requireObject(ATTR)
            Operand.Attr(node.required(ATTR).text())
        } else {
            literal(node)
        }

    /** A string literal: a prefix, a CIDR block, a set member. */
    private fun string(node: DocumentNode): Literal = Literal.Str(node.text())

    private fun literal(node: DocumentNode): Literal = when {
        node.json.isString -> Literal.Str(node.json.asString())
        node.json.isBoolean -> Literal.Bool(node.json.asBoolean())
        else -> node.fail("a literal is a JSON string or boolean")
    }

    private fun time(node: DocumentNode): Condition.Time {
        node.requireObject(*TIME_FIELDS)
        return Condition.Time(
            notBefore = node.field("notBefore")?.text(),
            notAfter = node.field("notAfter")?.text(),
            zone = node.field("zone")?.text(),
            daysOfWeek = node.field("daysOfWeek")?.let { days -> days.elements().map { it.text() } },
            from = node.field("from")?.text(),
            to = node.field("to")?.text(),
        )
    }

    fun write(condition: Condition): JsonNode {
        val node = factory.objectNode()
        when (condition) {
            is Condition.Time -> time(node.putObject("time"), condition)
            is Condition.Exists -> node.put("exists", condition.attribute)
            is Condition.And -> node.putArray(
                "and"
            ).also { array -> condition.conditions.forEach { array.add(write(it)) } }
            is Condition.Or -> node.putArray(
                "or"
            ).also { array -> condition.conditions.forEach { array.add(write(it)) } }
            is Condition.Not -> node.set("not", write(condition.condition))
            else -> binary(node, condition)
        }
        return node
    }

    private fun binary(node: ObjectNode, condition: Condition) {
        when (condition) {
            is Condition.Eq -> {
                node.putArray("eq").add(condition.attribute).add(operand(condition.value))
                if (condition.ignoreCase) node.put(IGNORE_CASE, true)
            }

            is Condition.Prefix -> {
                node.putArray("prefix").add(condition.attribute).add(literal(condition.value))
                if (condition.ignoreCase) node.put(IGNORE_CASE, true)
            }

            is Condition.In -> node.putArray("in").add(condition.attribute).add(literals(condition.values))
            is Condition.Contains -> node.putArray("in").add(literal(condition.value)).add(condition.attribute)
            is Condition.Cidr -> node.putArray("cidr").add(condition.attribute).add(literals(condition.blocks))
            else -> throw IllegalArgumentException("Not a binary condition: $condition")
        }
    }

    private fun literals(values: List<Literal>): JsonNode =
        factory.arrayNode().also { array -> values.forEach { array.add(literal(it)) } }

    private fun time(node: ObjectNode, time: Condition.Time) {
        time.notBefore?.let { node.put("notBefore", it) }
        time.notAfter?.let { node.put("notAfter", it) }
        time.zone?.let { node.put("zone", it) }
        time.daysOfWeek?.let { days -> node.putArray("daysOfWeek").also { array -> days.forEach(array::add) } }
        time.from?.let { node.put("from", it) }
        time.to?.let { node.put("to", it) }
    }

    private fun operand(operand: Operand): JsonNode = when (operand) {
        is Operand.Attr -> factory.objectNode().put(ATTR, operand.path)
        is Literal -> literal(operand)
    }

    private fun literal(literal: Literal): JsonNode = when (literal) {
        is Literal.Str -> factory.stringNode(literal.value)
        is Literal.Bool -> factory.booleanNode(literal.value)
    }
}
