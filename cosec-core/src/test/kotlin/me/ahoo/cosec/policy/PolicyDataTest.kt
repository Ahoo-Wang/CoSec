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
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.action.getPathVariables
import me.ahoo.cosec.policy.action.setPathVariables
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class PolicyDataTest {
    @Test
    fun verifyDoesNotReadPathVariablesFromPreviousCall() {
        val stalePathVariableCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } answers {
                secondArg<SecurityContext>().getPathVariables()?.get("id") == "123"
            }
        }
        val policy = PolicyData(
            id = "policy",
            category = "",
            name = "policy",
            description = "",
            type = PolicyType.CUSTOM,
            tenantId = "tenant",
            condition = stalePathVariableCondition,
            statements = listOf(StatementData(action = AllActionMatcher.INSTANCE)),
        )
        val securityContext = SimpleSecurityContext.anonymous().apply {
            setPathVariables(mapOf("id" to "123"))
        }

        policy.verify(EvaluateRequest(), securityContext)
            .assert().isEqualTo(VerifyResult.IMPLICIT_DENY)
    }

    @Test
    fun verifyDoesNotLeakPathVariablesBetweenStatements() {
        val neverMatchCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } returns false
        }
        val stalePathVariableCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } answers {
                secondArg<SecurityContext>().getPathVariables()?.get("id") == "123"
            }
        }
        val policy = PolicyData(
            id = "path-policy",
            category = "",
            name = "path-policy",
            description = "",
            type = PolicyType.CUSTOM,
            tenantId = "tenant",
            statements = listOf(
                StatementData(
                    effect = Effect.DENY,
                    action = PathActionMatcherFactory.INSTANCE.create("/orders/{id}".asConfiguration()),
                    condition = neverMatchCondition,
                ),
                StatementData(
                    effect = Effect.ALLOW,
                    action = AllActionMatcher.INSTANCE,
                    condition = stalePathVariableCondition,
                ),
            ),
        )
        val result = policy.verify(
            EvaluateRequest(path = "/orders/123"),
            SimpleSecurityContext.anonymous(),
        )

        result.assert().isEqualTo(VerifyResult.IMPLICIT_DENY)
    }
}
