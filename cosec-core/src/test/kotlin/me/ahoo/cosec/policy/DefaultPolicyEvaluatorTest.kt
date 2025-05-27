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
import me.ahoo.test.asserts.assert
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI

internal class DefaultPolicyEvaluatorTest {

    @Test
    fun evaluateDefaultRequest() {
        var evaluateRequest: Request = EvaluateRequest()
        evaluateRequest.method.assert().isEqualTo("POST")
        evaluateRequest.remoteIp.assert().isEqualTo("127.0.0.1")
        evaluateRequest.origin.assert().hasHost("mockOrigin")
        evaluateRequest.referer.assert().hasHost("mockReferer")
        evaluateRequest.getHeader("key").assert().isEqualTo("")
        evaluateRequest.getQuery("key").assert().isEqualTo("")
        evaluateRequest.attributes.assert().isEmpty()
        evaluateRequest = evaluateRequest.withAttributes(mapOf("key" to "value"))
        evaluateRequest.attributes.assert().containsKey("key")
    }

    @Test
    fun evaluateRequest() {
        val evaluateRequest: Request = EvaluateRequest(
            path = "/policies/hi",
            method = "GET",
            remoteIp = "127.0.0.2",
            origin = URI.create("mock"),
            referer = URI.create("mock"),
            headers = mapOf("key" to "value"),
            queries = mapOf("key" to "value"),
        )
        evaluateRequest.method.assert().isEqualTo("GET")
        evaluateRequest.remoteIp.assert().isEqualTo("127.0.0.2")
        evaluateRequest.origin.toString().assert().isEqualTo("mock")
        evaluateRequest.referer.toString().assert().isEqualTo("mock")
        evaluateRequest.getHeader("key").assert().isEqualTo("value")
        evaluateRequest.getQuery("key").assert().isEqualTo("value")
        evaluateRequest.attributes.assert().isEmpty()
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
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("me.ahoo.cosec.serialization.CoSecJsonSerializerTest#serializePolicyProvider")
    fun evaluate(policy: Policy) {
        DefaultPolicyEvaluator.evaluate(policy)
    }
}
