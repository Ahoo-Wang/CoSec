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
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import ognl.OgnlException
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files

/**
 * Security tests asserting that [OgnlConditionMatcher] is sandboxed: a policy-supplied OGNL expression
 * must NOT be able to reach dangerous classes, static methods, constructors or reflection gadgets.
 */
internal class OgnlConditionMatcherSecurityTest {

    private fun matcher(expression: String) =
        OgnlConditionMatcherFactory()
            .create(mapOf(OGNL_CONDITION_MATCHER_EXPRESSION_KEY to expression).asConfiguration())

    @ParameterizedTest
    @ValueSource(
        strings = [
            // static access to a dangerous class -> information disclosure
            "@java.lang.System@getProperties() != null",
            "@java.lang.System@getenv() != null",
            // reflection gadget via getClass()/classloader
            "request.getClass().getClassLoader() != null",
            "#context.principal.getClass() != null",
        ]
    )
    fun `should block dangerous expression`(expression: String) {
        val conditionMatcher = matcher(expression)
        assertThrows<OgnlException> {
            conditionMatcher.match(mockk(relaxed = true), mockk(relaxed = true))
        }
    }

    @Test
    fun `should block file system write via constructor`() {
        val target = Files.createTempDirectory("cosec-ognl").resolve("pwned.txt")
        val path = target.toString().replace("\\", "\\\\")
        val conditionMatcher = matcher("new java.io.FileOutputStream(\"$path\") != null")

        assertThrows<OgnlException> {
            conditionMatcher.match(mockk(relaxed = true), mockk(relaxed = true))
        }
        assertThat("sandbox must prevent the file from being created", target.toFile().exists(), `is`(false))
    }

    @Test
    fun `should still evaluate legitimate property navigation`() {
        val conditionMatcher = matcher("path == \"auth/login\"")
        val request = mockk<Request> { every { path } returns "auth/login" }
        assertThat(conditionMatcher.match(request, mockk()), `is`(true))
    }
}
