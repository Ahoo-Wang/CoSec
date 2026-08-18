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

import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.getVerifyContext
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import java.time.Instant

/**
 * Maps an authorization outcome to an [AuditEvent].
 */
object AuditEventExtractor {

    fun fromDecision(
        request: Request,
        context: SecurityContext,
        result: AuthorizeResult,
        elapsedNanos: Long,
    ): AuditEvent {
        val verifyContext = context.getVerifyContext()
        return eventOf(
            request = request,
            context = context,
            decision = toDecision(result),
            reason = result.reason,
            elapsedNanos = elapsedNanos,
            verifyContext = verifyContext,
        )
    }

    fun fromError(
        request: Request,
        context: SecurityContext,
        error: Throwable,
        elapsedNanos: Long,
    ): AuditEvent {
        val (decision, reason) = when (error) {
            is TooManyRequestsException -> AuditDecision.TOO_MANY_REQUESTS to AuthorizeResult.TOO_MANY_REQUESTS.reason
            is RegexTimeoutException -> AuditDecision.IMPLICIT_DENY to AuthorizeResult.IMPLICIT_DENY.reason
            else -> AuditDecision.ERROR to error.javaClass.simpleName
        }
        return eventOf(request, context, decision, reason, elapsedNanos, verifyContext = context.getVerifyContext())
    }

    private fun toDecision(result: AuthorizeResult): AuditDecision {
        return when {
            result.authorized -> AuditDecision.ALLOW
            AuthorizeResult.TOO_MANY_REQUESTS.reason == result.reason -> AuditDecision.TOO_MANY_REQUESTS
            AuthorizeResult.IMPLICIT_DENY.reason == result.reason -> AuditDecision.IMPLICIT_DENY
            else -> AuditDecision.EXPLICIT_DENY
        }
    }

    private fun eventOf(
        request: Request,
        context: SecurityContext,
        decision: AuditDecision,
        reason: String,
        elapsedNanos: Long,
        verifyContext: me.ahoo.cosec.authorization.VerifyContext?,
    ): AuditEvent {
        var matchedPolicyId: String? = null
        var matchedStatementName: String? = null
        var matchedRoleId: String? = null
        var matchedPermissionId: String? = null
        when (verifyContext) {
            is PolicyVerifyContext -> {
                matchedPolicyId = verifyContext.policy.id
                matchedStatementName = verifyContext.statement.name
            }

            is RoleVerifyContext -> {
                matchedRoleId = verifyContext.roleId
                matchedPermissionId = verifyContext.permission.id
            }

            null -> Unit
        }
        return AuditEvent(
            timestamp = Instant.now(),
            tenantId = context.tenant.tenantId,
            principalId = context.principal.id,
            authenticated = context.principal.authenticated,
            roles = context.principal.roles.toSet(),
            policies = context.principal.policies.toSet(),
            appId = request.appId.ifBlank { null },
            spaceId = request.spaceId.ifBlank { null },
            deviceId = request.deviceId.ifBlank { null },
            requestId = request.requestId.ifBlank { null },
            remoteIp = request.remoteIp,
            method = request.method,
            path = request.path,
            decision = decision,
            reason = reason,
            elapsedNanos = elapsedNanos,
            matchedPolicyId = matchedPolicyId,
            matchedStatementName = matchedStatementName,
            matchedRoleId = matchedRoleId,
            matchedPermissionId = matchedPermissionId,
        )
    }
}
