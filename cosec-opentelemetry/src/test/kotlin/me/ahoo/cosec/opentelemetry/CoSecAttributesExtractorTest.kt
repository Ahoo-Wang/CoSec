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

package me.ahoo.cosec.opentelemetry

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.principal.SimplePrincipal
import org.junit.jupiter.api.Test

class CoSecAttributesExtractorTest {
    @Test
    fun onStart() {
        CoSecAttributesExtractor.onStart(mockk(), mockk(), mockk())
    }

    @Test
    fun onEndAnonymous() {
        val attributes = Attributes.builder()
        SecurityContext
        CoSecAttributesExtractor.onEnd(
            attributes,
            Context.current(),
            SimpleSecurityContext.anonymous(),
            null,
            null
        )
    }

    @Test
    fun onEndPolicyVerifyContext() {
        val attributes = Attributes.builder()
        val request = mockk<Request> {
            every { appId } returns "appId"
            every { deviceId } returns "deviceId"
            every { requestId } returns "requestId"
        }
        val verifyContext = mockk<PolicyVerifyContext> {
            every { policy.id } returns "policyId"
            every { statementIndex } returns 1
            every { statement.name } returns "statementName"
            every { result } returns VerifyResult.IMPLICIT_DENY
        }
        val principal = SimplePrincipal(
            id = "id",
            policies = setOf("policyId1", "policyId2"),
            roles = setOf("roleId1", "roleId2"),
            attributes = emptyMap()
        )
        val securityContext = SimpleSecurityContext(principal)
        securityContext.setVerifyContext(verifyContext)
        securityContext.setRequest(request)
        CoSecAttributesExtractor.onEnd(
            attributes,
            Context.current(),
            securityContext,
            null,
            null
        )
    }

    @Test
    fun onEndRoleVerifyContext() {
        val attributes = Attributes.builder()
        val request = mockk<Request> {
            every { appId } returns "appId"
            every { deviceId } returns "deviceId"
            every { requestId } returns "requestId"
        }
        val verifyContext = mockk<RoleVerifyContext> {
            every { roleId } returns "roleId"
            every { permission } returns mockk {
                every { id } returns "permissionId"
            }
            every { result } returns VerifyResult.ALLOW
        }
        val securityContext = SimpleSecurityContext.anonymous()
        securityContext.setVerifyContext(verifyContext)
        securityContext.setRequest(request)
        CoSecAttributesExtractor.onEnd(
            attributes,
            Context.current(),
            securityContext,
            null,
            null
        )
    }

    @Test
    fun onEndNoneVerifyContext() {
        val attributes = Attributes.builder()
        val request = mockk<Request> {
            every { appId } returns ""
            every { deviceId } returns ""
            every { requestId } returns ""
        }
        val securityContext = SimpleSecurityContext.anonymous()
        securityContext.setRequest(request)
        CoSecAttributesExtractor.onEnd(
            attributes,
            Context.current(),
            securityContext,
            null,
            null
        )
    }
}
