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
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class StatementDataTest {

    @Test
    fun verifyDefault() {
        val statementData = StatementData()
        assertThat(statementData.verify(mockk(), mockk()), `is`(VerifyResult.IMPLICIT_DENY))
    }

    @Test
    fun verify() {
        val statementData = StatementData(actions = setOf(PathActionMatcher("auth/*")))
        val request = mockk<Request> {
            every { action } returns "auth/login:POST"
        }
        assertThat(statementData.verify(request, mockk()), `is`(VerifyResult.ALLOW))
    }

    @Test
    fun verifyWithCondition() {
        val statementData = StatementData(
            actions = setOf(ReplaceablePathActionMatcher("order/#{principal.id}/*")),
            conditions = setOf(SpelConditionMatcher("context.principal.authenticated()"))
        )
        val request = mockk<Request> {
            every { action } returns "order/1/search:POST"
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
            actions = setOf(AllActionMatcher)
        )
        assertThat(statementData.verify(mockk(), mockk()), `is`(VerifyResult.EXPLICIT_DENY))
    }
}
