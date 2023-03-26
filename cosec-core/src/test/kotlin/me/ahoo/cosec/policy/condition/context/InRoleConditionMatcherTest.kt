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

package me.ahoo.cosec.policy.condition.context

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.notNullValue

import org.junit.jupiter.api.Test

class InRoleConditionMatcherTest {
    @Test
    fun match() {
        val request: Request = mockk()
        val context: SecurityContext = mockk {
            every { principal.roles } returns setOf("roleId")
        }
        val conditionMatcher = InRoleConditionMatcherFactory()
            .create(
                mapOf(
                    "value" to "roleId",
                ).asConfiguration(),
            )
        assertThat(conditionMatcher.type, `is`(InRoleConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.configuration, notNullValue())
        assertThat(conditionMatcher.match(request, context), `is`(true))
    }
}