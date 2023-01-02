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

package me.ahoo.cosec.policy.condition

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.MATCHER_PATTERN_KEY
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class OgnlConditionMatcherTest {

    @ParameterizedTest
    @MethodSource("parametersForActions")
    fun matchRequest(expression: String, actions: List<String>, expected: Boolean) {
        val conditionMatcher = OgnlConditionMatcher(mapOf(MATCHER_PATTERN_KEY to expression).asConfiguration())
        actions.forEach {
            val request = mockk<Request> {
                every { path } returns it
            }
            assertThat(conditionMatcher.match(request, mockk()), `is`(expected))
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForContext")
    fun matchContext(expression: String, principalIds: List<String>, expected: Boolean) {
        val conditionMatcher = OgnlConditionMatcher(mapOf(MATCHER_PATTERN_KEY to expression).asConfiguration())
        principalIds.forEach {
            val context = mockk<SecurityContext> {
                every { principal } returns mockk {
                    every { id } returns it
                }
            }
            assertThat(conditionMatcher.match(mockk(), context), `is`(expected))
        }
    }

    companion object {
        @JvmStatic
        fun parametersForActions(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("path == \"auth/login\"", listOf("auth/login"), true),
                Arguments.of("path == \"auth/login\"", listOf("auth/logout"), false)
            )
        }

        @JvmStatic
        fun parametersForContext(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("#context.principal.id == \"1\"", listOf("1"), true),
                Arguments.of("#context.principal.id == \"1\"", listOf("2"), false)
            )
        }
    }
}
