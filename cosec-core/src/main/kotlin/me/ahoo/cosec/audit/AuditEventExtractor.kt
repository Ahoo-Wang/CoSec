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
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.audit.AuditTrace
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.BlacklistVerifyContext
import me.ahoo.cosec.authorization.NoMatchVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifySource
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.getVerifyContext
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import java.time.Instant
import java.util.UUID

/**
 * Maps an authorization outcome to an [AuditEvent].
 */
object AuditEventExtractor {

    fun fromDecision(
        request: Request,
        context: SecurityContext,
        result: AuthorizeResult,
        elapsedNanos: Long,
        verifyContext: VerifyContext? = context.getVerifyContext(),
    ): AuditEvent {
        val decision = toDecision(result)
        return eventOf(
            request = request,
            context = context,
            decision = decision,
            reasonCode = toReasonCode(result, decision),
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
        verifyContext: VerifyContext? = context.getVerifyContext(),
    ): AuditEvent {
        val (decision, reasonCode, reason) = when (error) {
            is TooManyRequestsException -> Triple(
                AuditDecision.TOO_MANY_REQUESTS,
                AuditReasonCode.TOO_MANY_REQUESTS,
                AuthorizeResult.TOO_MANY_REQUESTS.reason,
            )

            is RegexTimeoutException -> Triple(
                AuditDecision.IMPLICIT_DENY,
                AuditReasonCode.REGEX_TIMEOUT,
                AuthorizeResult.IMPLICIT_DENY.reason,
            )

            else -> Triple(AuditDecision.ERROR, AuditReasonCode.ERROR, error.javaClass.simpleName)
        }
        return eventOf(request, context, decision, reasonCode, reason, elapsedNanos, verifyContext)
    }

    private fun toDecision(result: AuthorizeResult): AuditDecision {
        return when {
            result.authorized -> AuditDecision.ALLOW
            AuthorizeResult.TOO_MANY_REQUESTS.reason == result.reason -> AuditDecision.TOO_MANY_REQUESTS
            AuthorizeResult.IMPLICIT_DENY.reason == result.reason -> AuditDecision.IMPLICIT_DENY
            else -> AuditDecision.EXPLICIT_DENY
        }
    }

    private fun toReasonCode(result: AuthorizeResult, decision: AuditDecision): AuditReasonCode {
        return when (result.reason) {
            AuthorizeResult.TOKEN_EXPIRED.reason -> AuditReasonCode.TOKEN_EXPIRED
            TOKEN_INVALID_REASON -> AuditReasonCode.TOKEN_INVALID
            else -> when (decision) {
                AuditDecision.ALLOW -> AuditReasonCode.ALLOW
                AuditDecision.EXPLICIT_DENY -> AuditReasonCode.EXPLICIT_DENY
                AuditDecision.IMPLICIT_DENY -> AuditReasonCode.IMPLICIT_DENY
                AuditDecision.TOO_MANY_REQUESTS -> AuditReasonCode.TOO_MANY_REQUESTS
                AuditDecision.ERROR -> AuditReasonCode.ERROR
            }
        }
    }

    private fun eventOf(
        request: Request,
        context: SecurityContext,
        decision: AuditDecision,
        reasonCode: AuditReasonCode,
        reason: String,
        elapsedNanos: Long,
        verifyContext: VerifyContext?,
    ): AuditEvent {
        return AuditEventData(
            eventId = UUID.randomUUID().toString(),
            timestamp = Instant.now(),
            tenantId = context.tenant.tenantId,
            principal = AuditPrincipalData(
                id = context.principal.id,
                authenticated = context.principal.authenticated,
                roles = context.principal.roles.toSet(),
                policies = context.principal.policies.toSet(),
            ),
            request = AuditRequestData(
                id = request.requestId.ifBlank { null },
                appId = request.appId.ifBlank { null },
                spaceId = request.spaceId.ifBlank { null },
                remoteIp = request.remoteIp,
                method = request.method,
                path = request.path,
                routeId = request.attributes[AuditRequest.ROUTE_ID_ATTRIBUTE_KEY],
                device = AuditDeviceData(
                    id = request.deviceId.ifBlank { null },
                    userAgent = request.getHeader(USER_AGENT_HEADER).ifBlank { null },
                ),
            ),
            authorization = AuditAuthorizationData(
                decision = decision,
                reasonCode = reasonCode,
                reason = reason,
                elapsedNanos = elapsedNanos,
                source = sourceOf(verifyContext),
            ),
            trace = traceOf(request),
        )
    }

    private fun sourceOf(verifyContext: VerifyContext?): AuditSourceData {
        return when (verifyContext) {
            is PolicyVerifyContext -> AuditSourceData(
                type = when (verifyContext.source) {
                    PolicyVerifySource.GLOBAL -> AuditSourceType.GLOBAL_POLICY
                    PolicyVerifySource.PRINCIPAL -> AuditSourceType.PRINCIPAL_POLICY
                },
                policy = AuditPolicyData(
                    id = verifyContext.policy.id,
                    type = verifyContext.policy.type,
                    statement = AuditStatementData(
                        index = verifyContext.statementIndex,
                        name = verifyContext.statement.name,
                    ),
                ),
            )

            is RoleVerifyContext -> AuditSourceData(
                type = AuditSourceType.ROLE_PERMISSION,
                role = AuditRoleData(
                    id = verifyContext.roleId,
                    permission = AuditPermissionData(
                        id = verifyContext.permission.id,
                        name = verifyContext.permission.name,
                    ),
                ),
            )

            RootVerifyContext -> AuditSourceData(AuditSourceType.ROOT)
            BlacklistVerifyContext -> AuditSourceData(AuditSourceType.BLACKLIST)
            NoMatchVerifyContext -> AuditSourceData(AuditSourceType.NONE)
            null -> AuditSourceData(AuditSourceType.UNKNOWN)
            else -> AuditSourceData(AuditSourceType.UNKNOWN)
        }
    }

    private fun traceOf(request: Request): AuditTraceData? {
        val traceId = request.attributes[AuditTrace.TRACE_ID_ATTRIBUTE_KEY] ?: return null
        val spanId = request.attributes[AuditTrace.SPAN_ID_ATTRIBUTE_KEY] ?: return null
        return AuditTraceData(traceId = traceId, spanId = spanId)
    }

    private const val USER_AGENT_HEADER = "User-Agent"
    private const val TOKEN_INVALID_REASON = "Token Invalid"
}
