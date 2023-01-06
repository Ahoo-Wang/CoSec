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

package me.ahoo.cosec.policy

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.SpelConditionMatcherFactory
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class StatementDataTest {

    @Test
    fun verifyDefault() {
        val statementData = StatementData()
        assertThat(statementData.name, equalTo(""))
        assertThat(statementData.effect, equalTo(Effect.ALLOW))
        assertThat(statementData.actions, equalTo(listOf()))
        assertThat(statementData.conditions, equalTo(listOf()))
        assertThat(statementData.verify(mockk(), mockk()), `is`(VerifyResult.IMPLICIT_DENY))
    }

    @Test
    fun verify() {
        val statementData = StatementData(
            actions = listOf(
                PathActionMatcherFactory().create(
                    mapOf(
                        MATCHER_PATTERN_KEY to "auth/*"
                    ).asConfiguration()
                )
            )
        )
        val request = mockk<Request> {
            every { path } returns "auth/login:POST"
        }
        assertThat(statementData.verify(request, mockk()), `is`(VerifyResult.ALLOW))
    }

    @Test
    fun verifyWithCondition() {
        val statementData = StatementData(
            actions = listOf(
                PathActionMatcherFactory().create(
                    mapOf(
                        MATCHER_PATTERN_KEY to "order/#{principal.id}/*"
                    ).asConfiguration()
                )
            ),
            conditions = listOf(
                SpelConditionMatcherFactory().create(
                    mapOf(
                        MATCHER_PATTERN_KEY to "context.principal.authenticated()"
                    ).asConfiguration()
                )
            )
        )
        val request = mockk<Request> {
            every { path } returns "order/1/search:POST"
        }
        val securityContext = mockk<SecurityContext> {
            every { principal } returns mockk {
                every { id } returns "1"
                every { authenticated() } returns true
            }
        }
        assertThat(statementData.verify(request, securityContext), `is`(VerifyResult.ALLOW))

        val securityContextNotMine = mockk<SecurityContext> {
            every { principal } returns mockk {
                every { id } returns "2"
                every { authenticated() } returns true
            }
        }
        assertThat(statementData.verify(request, securityContextNotMine), `is`(VerifyResult.IMPLICIT_DENY))
    }

    @Test
    fun verifyDeny() {
        val statementData = StatementData(
            effect = Effect.DENY,
            actions = listOf(AllActionMatcher(JsonConfiguration.EMPTY))
        )
        assertThat(statementData.verify(mockk(), mockk()), `is`(VerifyResult.EXPLICIT_DENY))
    }
}
