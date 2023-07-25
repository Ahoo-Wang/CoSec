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
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.condition.SpelConditionMatcherFactory
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

internal class StatementDataTest {

    @Test
    fun verifyDefault() {
        val statementData = StatementData(action = AllActionMatcher.INSTANCE)
        assertThat(statementData.name, equalTo(""))
        assertThat(statementData.effect, equalTo(Effect.ALLOW))
        assertThat(statementData.action, equalTo(AllActionMatcher.INSTANCE))
        assertThat(statementData.condition, instanceOf(AllConditionMatcher::class.java))
        assertThat(statementData.verify(mockk(), mockk()), `is`(VerifyResult.ALLOW))
    }

    @Test
    fun verify() {
        val statementData = StatementData(
            action = PathActionMatcherFactory().create(
                mapOf(
                    "pattern" to "auth/*",
                ).asConfiguration(),
            ),
        )
        val request = mockk<Request> {
            every { path } returns "auth/login:POST"
        }
        assertThat(statementData.verify(request, SimpleSecurityContext.anonymous()), `is`(VerifyResult.ALLOW))
    }

    @Test
    fun verifyWithCondition() {
        val statementData = StatementData(
            action = PathActionMatcherFactory().create(
                mapOf(
                    "pattern" to "order/#{principal.id}/*",
                ).asConfiguration(),
            ),
            condition = SpelConditionMatcherFactory().create(
                mapOf(
                    "expression" to "context.principal.authenticated()",
                ).asConfiguration(),
            ),
        )
        val request = mockk<Request> {
            every { path } returns "order/1/search:POST"
        }
        val securityContext = SimpleSecurityContext(
            principal = mockk {
                every { id } returns "1"
                every { authenticated() } returns true
            }
        )

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
            action = AllActionMatcher.INSTANCE,
        )
        assertThat(statementData.verify(mockk(), mockk()), `is`(VerifyResult.EXPLICIT_DENY))
    }
}
