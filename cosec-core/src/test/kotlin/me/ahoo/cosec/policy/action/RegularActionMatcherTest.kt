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

package me.ahoo.cosec.policy.action

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.Request
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class RegularActionMatcherTest {

    @ParameterizedTest
    @MethodSource("parameters")
    fun match(pattern: String, actions: List<String>, expected: Boolean) {
        val actionMatcher = RegularActionMatcher(pattern)
        actions.forEach {
            val request = mockk<Request> {
                every { action } returns it
            }
            assertThat(actionMatcher.match(request, mockk()), `is`(expected))
        }
    }

    companion object {
        @JvmStatic
        fun parameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("auth/login:POST", listOf("auth/login:POST"), true),
                Arguments.of("auth/login:POST", listOf("auth/logout:POST"), false),
                Arguments.of("auth/[a-zA-Z]+", listOf("auth/login", "auth/logout"), true),
                Arguments.of("auth/[a-zA-Z]+", listOf("auth/", "auth/login/"), false)
            )
        }
    }
}
