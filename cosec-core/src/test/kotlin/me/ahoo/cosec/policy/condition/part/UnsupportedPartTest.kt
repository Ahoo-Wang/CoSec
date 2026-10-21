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

package me.ahoo.cosec.policy.condition.part

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A condition on a part [DefaultPartExtractor] cannot read is rejected when it loads, as rate limit keys are,
 * instead of failing every matching request with 500.
 */
class UnsupportedPartTest {
    private val factories: List<ConditionMatcherFactory> = listOf(
        EqConditionMatcherFactory(),
        InConditionMatcherFactory(),
        ContainsConditionMatcherFactory(),
        StartsWithConditionMatcherFactory(),
        EndsWithConditionMatcherFactory(),
        RegularConditionMatcherFactory(),
        PathConditionMatcherFactory(),
    )

    @ParameterizedTest
    @ValueSource(strings = ["request.ip", "request.header.", "principal.id", ""])
    fun createWhenPartUnsupported(part: String) {
        factories.forEach { factory ->
            val error = assertThrows<IllegalArgumentException> {
                factory.create(
                    mapOf(
                        CONDITION_MATCHER_PART_KEY to part,
                        CONDITION_MATCHER_VALUE_KEY to "value",
                        "pattern" to "value",
                    ).asConfiguration()
                )
            }
            error.message.assert().contains("[$part]")
        }
    }

    @Test
    fun createWhenPartSupported() {
        factories.forEach { factory ->
            factory.create(
                mapOf(
                    CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                    CONDITION_MATCHER_VALUE_KEY to "value",
                    "pattern" to "value",
                ).asConfiguration()
            ).type.assert().isEqualTo(factory.type)
        }
    }

    @Test
    fun parsePolicyWhenConditionPartUnsupported() {
        val json = """
            {
              "id": "p",
              "name": "p",
              "type": "global",
              "tenantId": "(platform)",
              "statements": [
                { "action": "/orders/**", "condition": { "eq": { "part": "request.ip", "value": "10.0.0.1" } } }
              ]
            }
        """.trimIndent()
        val error = assertThrows<Exception> { CoSecJsonSerializer.readValue(json, Policy::class.java) }
        generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("[request.ip]") }
            .assert().isTrue()
    }
}
