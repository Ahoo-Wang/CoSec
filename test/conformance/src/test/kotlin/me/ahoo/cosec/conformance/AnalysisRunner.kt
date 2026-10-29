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
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.ConditionContainment
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.bool
import me.ahoo.cosec.testkit.fields
import me.ahoo.cosec.testkit.instant
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode

/**
 * The `analysis` runner: the analyses of RFC 0005 §4 over the conditions of a scenario, evaluated on one request
 * written as attribute values. The index and the grant check that use them come later (RFC 0005 PR-4, RFC 0007 PR-6);
 * the rules hold for the functions now.
 *
 * ```jsonc
 * "analysis": { "condition": {...}, "attribute": "scope.app",      // index keys (ANA-1)
 *               "c1": {...}, "c2": {...},                         // implies(c1, c2) (ANA-2)
 *               "request": { "scope.app": "a", "subject.roles": ["x"], "environment.ip": "10.0.0.1" } }
 * ```
 *
 * The outcome holds the keys and whether they admit the request's value, or what `implies` answered, and how each
 * condition evaluated on the request.
 */
object AnalysisRunner : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome {
        scenario.node.only("\$comment", "rules", "polarity", "level", "description", "analysis", "expect")
        val analysis = scenario.node.required("analysis")
        analysis.only("condition", "attribute", "c1", "c2", "request")
        val input = AttributeInput(analysis.get("request")?.fields().orEmpty())
        val evaluations = LinkedHashMap<String, Boolean>()
        val conditions = listOf("condition", "c1", "c2").mapNotNull { name ->
            analysis.get(name)?.let { name to ConditionReader.check(it) }
        }.toMap()
        conditions.forEach { (name, condition) -> evaluations[name] = condition.evaluate(input) }
        var keys: IndexKeys? = null
        var admits: Boolean? = null
        analysis.get("attribute")?.let {
            val attribute = Attribute.parse(it.text()) ?: throw ScenarioFormatException("Unknown attribute $it")
            val condition = conditions["condition"] ?: throw ScenarioFormatException("Index keys need a condition")
            keys = IndexKeys.of(condition, attribute).also { found -> admits = found.admits(input.string(attribute)) }
        }
        val c1 = conditions["c1"]
        val c2 = conditions["c2"]
        val implies = if (c1 != null && c2 != null) ConditionContainment.implies(c1, c2) else null
        return Outcome.Analyzed(keys, admits, implies, evaluations)
    }
}

/** Attribute values by path; an attribute the request does not list is absent. */
private class AttributeInput(private val values: Map<String, JsonNode>) : ConditionInput {
    private fun value(attribute: Attribute, type: AttributeType): JsonNode? {
        check(attribute.type == type) { "$attribute is not $type" }
        return values[attribute.path]
    }

    override fun string(attribute: Attribute): String? = value(attribute, AttributeType.STRING)?.text()

    override fun bool(attribute: Attribute): Boolean? = value(attribute, AttributeType.BOOL)?.bool()

    override fun stringSet(attribute: Attribute): Set<String>? =
        value(attribute, AttributeType.STRING_SET)?.strings()?.toSet()

    override fun ip(attribute: Attribute): IpAddress? = value(
        attribute,
        AttributeType.IP
    )?.text()?.let(IpAddress::parse)

    override fun time(attribute: Attribute): Long = value(attribute, AttributeType.INSTANT)?.instant() ?: ConditionInput.NO_TIME
}
