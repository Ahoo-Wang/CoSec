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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.conformance

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.condition.CheckResult
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import me.ahoo.cosec.testkit.DocumentException
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.bool
import me.ahoo.cosec.testkit.fields
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode

/**
 * Reads the condition syntax of RFC 0005 §3.1 into a [Condition] and checks it. The document codec of RFC 0004 PR-4
 * replaces it; until then the suite needs its own reader, and it is written from the RFC, not from a codec.
 */
object ConditionReader {
    private val checker = ConditionChecker()

    /** The checked condition, or [DocumentException] with the checker's errors. */
    fun check(node: JsonNode): CheckedCondition =
        when (val result = checker.check(read(node))) {
            is CheckResult.Valid -> result.condition
            is CheckResult.Invalid -> throw DocumentException(
                "Condition does not compile: " + result.errors.joinToString { "${it.path} ${it.message}" }
            )
        }

    fun read(node: JsonNode): Condition {
        val fields = node.fields()
        val operator = (fields.keys - IGNORE_CASE).singleOrNull() ?: malformed("A condition has exactly one operator: $node")
        val ignoreCase = fields[IGNORE_CASE]?.bool()
        if (ignoreCase != null && operator != "eq" && operator != "prefix") {
            malformed("ignoreCase applies to eq and prefix only: $node")
        }
        return node(operator, fields.getValue(operator), ignoreCase ?: false)
    }

    private fun node(operator: String, operands: JsonNode, ignoreCase: Boolean): Condition = when (operator) {
        "eq", "in", "prefix", "cidr" -> binary(operator, operands, ignoreCase)
        "time" -> time(operands)
        "exists" -> Condition.Exists(operands.text())
        "and" -> Condition.And(operands.values().map(::read))
        "or" -> Condition.Or(operands.values().map(::read))
        "not" -> Condition.Not(read(operands))
        else -> malformed("Unknown condition operator $operator")
    }

    /** The operators written `[left, right]`. */
    private fun binary(operator: String, operands: JsonNode, ignoreCase: Boolean): Condition {
        if (!operands.isArray || operands.size() != 2) malformed("Expected [left, right], found $operands")
        val left = operands[0]
        val right = operands[1]
        return when (operator) {
            "eq" -> Condition.Eq(left.text(), operand(right), ignoreCase)
            "prefix" -> Condition.Prefix(left.text(), literal(right), ignoreCase)
            "cidr" -> Condition.Cidr(left.text(), right.values().map(::literal))
            // [attribute, [literals]] or [literal, set attribute]
            else -> if (right.isArray) {
                Condition.In(left.text(), right.values().map(::literal))
            } else {
                Condition.Contains(literal(left), right.text())
            }
        }
    }

    private fun malformed(message: String): Nothing = throw ScenarioFormatException(message)

    private fun operand(node: JsonNode): Operand =
        if (node.isObject) {
            node.only("attr")
            Operand.Attr(node.required("attr").text())
        } else {
            literal(node)
        }

    private fun literal(node: JsonNode): Literal = when {
        node.isString -> Literal.Str(node.asString())
        node.isBoolean -> Literal.Bool(node.asBoolean())
        else -> throw ScenarioFormatException("A literal is a JSON string or boolean, found $node")
    }

    private fun time(node: JsonNode): Condition.Time {
        node.only("notBefore", "notAfter", "zone", "daysOfWeek", "from", "to")
        return Condition.Time(
            notBefore = node.get("notBefore")?.text(),
            notAfter = node.get("notAfter")?.text(),
            zone = node.get("zone")?.text(),
            daysOfWeek = node.get("daysOfWeek")?.strings(),
            from = node.get("from")?.text(),
            to = node.get("to")?.text(),
        )
    }

    private const val IGNORE_CASE = "ignoreCase"
}
