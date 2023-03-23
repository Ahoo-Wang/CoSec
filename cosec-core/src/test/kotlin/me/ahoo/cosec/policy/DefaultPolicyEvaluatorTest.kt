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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

internal class DefaultPolicyEvaluatorTest {

    @Test
    fun evaluateRequest() {
        var evaluateRequest: Request = EvaluateRequest()
        assertThat(evaluateRequest.method, equalTo("POST"))
        assertThat(evaluateRequest.remoteIp, equalTo("127.0.0.1"))
        assertThat(evaluateRequest.origin, equalTo("mockOrigin"))
        assertThat(evaluateRequest.referer, equalTo("mockReferer"))
        assertThat(evaluateRequest.getHeader("key"), equalTo("key"))
        assertThat(evaluateRequest.attributes, equalTo(mapOf()))
        evaluateRequest = evaluateRequest.withAttributes(mapOf("key" to "value"))
        assertThat(evaluateRequest.attributes["key"], equalTo("value"))
    }

    @Test
    fun mockRequest() {
        val statement = object : Statement {
            override val name: String
                get() = "name"
            override val effect: Effect
                get() = Effect.ALLOW
            override val action = AllActionMatcher.INSTANCE
            override val condition: ConditionMatcher
                get() = AllConditionMatcher.INSTANCE

            override fun verify(request: Request, securityContext: SecurityContext): VerifyResult {
                assertThat(request, instanceOf(EvaluateRequest::class.java))
                return VerifyResult.ALLOW
            }
        }

        DefaultPolicyEvaluator.evaluate(
            PolicyData(
                id = "1",
                category = "auth",
                name = "auth",
                description = "",
                type = PolicyType.CUSTOM,
                tenantId = "1",
                statements = listOf(statement),
            )
        )
    }

    @ParameterizedTest
    @MethodSource("me.ahoo.cosec.serialization.CoSecJsonSerializerTest#serializePolicyProvider")
    fun evaluate(policy: Policy) {
        DefaultPolicyEvaluator.evaluate(policy)
    }
}
