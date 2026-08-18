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

package me.ahoo.cosec.audit

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

class AuditEventExtractorTest {
    private val request = mockk<Request> {
        every { appId } returns "app"
        every { spaceId } returns "0"
        every { deviceId } returns ""
        every { requestId } returns "req-1"
        every { remoteIp } returns "1.2.3.4"
        every { method } returns "POST"
        every { path } returns "/api/orders"
    }
    private val principal = mockk<CoSecPrincipal> {
        every { id } returns "u1"
        every { authenticated } returns true
        every { roles } returns setOf("admin@0")
        every { policies } returns setOf("p1")
    }
    private val attributeMap = mutableMapOf<String, Any>()
    private val context = mockk<SecurityContext> {
        every { principal } returns this@AuditEventExtractorTest.principal
        every { tenant } returns mockk<Tenant> { every { tenantId } returns "t1" }
        every { attributes } returns this@AuditEventExtractorTest.attributeMap
        every { setAttributeValue(any(), any()) } answers {
            attributeMap[firstArg()] = secondArg()
            self as SecurityContext
        }
        every { getAttributeValue<Any>(any()) } answers { attributeMap[firstArg()] }
    }

    @Test
    fun fromDecisionWhenAllow() {
        val event = AuditEventExtractor.fromDecision(request, context, AuthorizeResult.ALLOW, elapsedNanos = 100)
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.ALLOW)
        event.reason.assert().isEqualTo("Allow")
        event.elapsedNanos.assert().isEqualTo(100)
        event.deviceId.assert().isNull()
        event.tenantId.assert().isEqualTo("t1")
        event.principalId.assert().isEqualTo("u1")
        event.roles.assert().isEqualTo(setOf("admin@0"))
        Instant.ofEpochMilli(0).assert().isBefore(event.timestamp)
    }

    @Test
    fun fromDecisionWhenExplicitDenyWithPolicyVerifyContext() {
        val policy = mockk<Policy> { every { id } returns "deny-write" }
        val statement = mockk<Statement> { every { name } returns "deny-orders-write" }
        context.setVerifyContext(
            PolicyVerifyContext(policy, statementIndex = 0, statement = statement, result = VerifyResult.EXPLICIT_DENY),
        )
        val event = AuditEventExtractor.fromDecision(request, context, AuthorizeResult.EXPLICIT_DENY, elapsedNanos = 1)
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.EXPLICIT_DENY)
        event.matchedPolicyId.assert().isEqualTo("deny-write")
        event.matchedStatementName.assert().isEqualTo("deny-orders-write")
        event.matchedRoleId.assert().isNull()
    }

    @Test
    fun fromDecisionWhenRoleVerifyContext() {
        val permission = mockk<Permission> { every { id } returns "permissionId" }
        context.setVerifyContext(
            RoleVerifyContext(roleId = "admin", permission = permission, result = VerifyResult.ALLOW)
        )
        val event = AuditEventExtractor.fromDecision(request, context, AuthorizeResult.ALLOW, elapsedNanos = 1)
        event.matchedRoleId.assert().isEqualTo("admin")
        event.matchedPermissionId.assert().isEqualTo("permissionId")
        event.matchedPolicyId.assert().isNull()
    }

    @Test
    fun fromDecisionWhenImplicitDeny() {
        val event = AuditEventExtractor.fromDecision(request, context, AuthorizeResult.IMPLICIT_DENY, elapsedNanos = 1)
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.IMPLICIT_DENY)
    }

    @Test
    fun fromDecisionWhenTooManyRequests() {
        val event = AuditEventExtractor.fromDecision(
            request,
            context,
            AuthorizeResult.TOO_MANY_REQUESTS,
            elapsedNanos = 1,
        )
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.TOO_MANY_REQUESTS)
    }

    @Test
    fun snapshotPrincipalGrants() {
        val roles = mutableSetOf("admin@0")
        val policies = mutableSetOf("p1")
        every { principal.roles } returns roles
        every { principal.policies } returns policies
        val event = AuditEventExtractor.fromDecision(request, context, AuthorizeResult.ALLOW, elapsedNanos = 1)
        roles += "operator@0"
        policies += "p2"
        event.roles.assert().isEqualTo(setOf("admin@0"))
        event.policies.assert().isEqualTo(setOf("p1"))
    }

    @Test
    fun fromDecisionWhenTokenExpiredCollapsesToExplicitDeny() {
        val event = AuditEventExtractor.fromDecision(request, context, AuthorizeResult.TOKEN_EXPIRED, elapsedNanos = 1)
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.EXPLICIT_DENY)
        event.reason.assert().isEqualTo("Token Expired")
    }

    @Test
    fun fromErrorWhenTooManyRequests() {
        val event = AuditEventExtractor.fromError(
            request,
            context,
            me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException(),
            elapsedNanos = 1,
        )
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.TOO_MANY_REQUESTS)
        event.reason.assert().isEqualTo("Too Many Requests")
    }

    @Test
    fun fromErrorWhenRegexTimeout() {
        val event = AuditEventExtractor.fromError(request, context, RegexTimeoutException("timeout"), elapsedNanos = 1)
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.IMPLICIT_DENY)
        event.reason.assert().isEqualTo("Implicit Deny")
    }

    @Test
    fun fromErrorWhenUnexpected() {
        val event = AuditEventExtractor.fromError(request, context, IllegalStateException("boom"), elapsedNanos = 1)
        event.decision.assert().isEqualTo(me.ahoo.cosec.api.audit.AuditDecision.ERROR)
        event.reason.assert().isEqualTo("IllegalStateException")
    }
}
