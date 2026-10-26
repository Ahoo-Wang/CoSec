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

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * One evaluation of each operator (RFC 0005 §9.3 `ConditionBenchmark`), on a request it matches. Expected
 * allocation: none for every operator (`-PjmhProfilers=gc`).
 *
 * `and` is the twelve-operand conjunction of all the others, which a statement's condition rarely exceeds.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class ConditionBenchmark {
    @Param("eq", "eqIgnoreCase", "attrEq", "in", "contains", "prefix", "labels", "cidr", "time", "exists", "not", "and")
    lateinit var operator: String

    private lateinit var condition: CheckedCondition
    private val input = Input()

    @Setup
    fun setup() {
        val conditions = mapOf(
            "eq" to Condition.Eq("subject.id", Literal.Str("alice")),
            "eqIgnoreCase" to Condition.Eq("subject.id", Literal.Str("ALICE"), ignoreCase = true),
            "attrEq" to Condition.Eq("resource.var.owner", Operand.Attr("subject.id")),
            "in" to Condition.In("scope.space", List(64) { Literal.Str("team-$it") }),
            "contains" to Condition.Contains(Literal.Str("admin"), "scope.roles"),
            "prefix" to Condition.Prefix("resource.id", Literal.Str("/orders/")),
            "labels" to Condition.Prefix("http.origin.host.labels", Literal.Str("com.example")),
            "cidr" to Condition.Cidr("environment.ip", List(256) { Literal.Str("10.${it}.0.0/16") }),
            "time" to Condition.Time(zone = "+08:00", daysOfWeek = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"), from = "00:00", to = "23:59:59.999999999"),
            "exists" to Condition.Exists("environment.ip"),
            "not" to Condition.Not(Condition.Exists("http.header.x-debug")),
        )
        val source = if (operator == "and") Condition.And(conditions.values.toList()) else conditions.getValue(operator)
        val result = ConditionChecker().check(source)
        check(result is CheckResult.Valid) { (result as CheckResult.Invalid).errors.toString() }
        condition = ConditionNormalizer.normalize(result.condition)
        check(condition.evaluate(input)) { "The benchmark request must match $condition." }
    }

    @Benchmark
    fun evaluate(): Boolean = condition.evaluate(input)

    private class Input : ConditionInput {
        private val strings = mapOf(
            Attribute.of(Attribute.Kind.SUBJECT_ID) to "alice",
            Attribute.of(Attribute.Kind.RESOURCE_VAR, "owner") to "alice",
            Attribute.of(Attribute.Kind.SCOPE_SPACE) to "team-63",
            Attribute.of(Attribute.Kind.RESOURCE_ID) to "/orders/1",
            Attribute.of(Attribute.Kind.HTTP_ORIGIN_HOST) to "api.example.com",
        )
        private val roles = setOf("admin", "dev")
        private val ip = requireNotNull(IpAddress.parse("10.255.1.1"))

        override fun string(attribute: Attribute): String? = strings[attribute]

        override fun bool(attribute: Attribute): Boolean? = null

        override fun stringSet(attribute: Attribute): Set<String>? = roles

        override fun ip(attribute: Attribute): IpAddress = ip

        override fun time(attribute: Attribute): Long = 1_800_000_000_000
    }
}
