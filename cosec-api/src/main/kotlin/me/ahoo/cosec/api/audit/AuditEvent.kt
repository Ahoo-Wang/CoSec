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

package me.ahoo.cosec.api.audit

import me.ahoo.cosec.api.policy.PolicyType
import java.time.Instant

enum class AuditDecision {
    ALLOW,
    EXPLICIT_DENY,
    IMPLICIT_DENY,
    TOO_MANY_REQUESTS,
    ERROR,
}

enum class AuditReasonCode {
    ALLOW,
    EXPLICIT_DENY,
    IMPLICIT_DENY,
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    TOO_MANY_REQUESTS,
    REGEX_TIMEOUT,
    ERROR,
}

enum class AuditSourceType {
    ROOT,
    BLACKLIST,
    GLOBAL_POLICY,
    PRINCIPAL_POLICY,
    ROLE_PERMISSION,
    NONE,
    UNKNOWN,
}

data class AuditPrincipal(
    val id: String,
    val authenticated: Boolean,
    val roles: Set<String>,
    val policies: Set<String>,
)

data class AuditDevice(
    val id: String?,
    val userAgent: String?,
)

data class AuditRequest(
    val id: String?,
    val appId: String?,
    val spaceId: String?,
    val remoteIp: String,
    val method: String,
    val path: String,
    val routeId: String?,
    val device: AuditDevice,
) {
    companion object {
        const val ROUTE_ID_ATTRIBUTE_KEY = "cosec.audit.routeId"
    }
}

data class AuditStatement(
    val index: Int,
    val name: String,
)

data class AuditPolicy(
    val id: String,
    val type: PolicyType,
    val statement: AuditStatement,
)

data class AuditPermission(
    val id: String,
    val name: String,
)

data class AuditRole(
    val id: String,
    val permission: AuditPermission,
)

data class AuditSource(
    val type: AuditSourceType,
    val policy: AuditPolicy? = null,
    val role: AuditRole? = null,
)

data class AuditAuthorization(
    val decision: AuditDecision,
    val reasonCode: AuditReasonCode,
    val reason: String,
    val elapsedNanos: Long,
    val source: AuditSource,
)

data class AuditTrace(
    val traceId: String,
    val spanId: String,
) {
    companion object {
        const val TRACE_ID_ATTRIBUTE_KEY = "cosec.audit.traceId"
        const val SPAN_ID_ATTRIBUTE_KEY = "cosec.audit.spanId"
    }
}

data class AuditEvent(
    val eventId: String,
    val timestamp: Instant,
    val tenantId: String,
    val principal: AuditPrincipal,
    val request: AuditRequest,
    val authorization: AuditAuthorization,
    val trace: AuditTrace?,
)

/**
 * SPI for consuming audit events.
 *
 * Implementations must be non-blocking; thrown exceptions are
 * swallowed by [me.ahoo.cosec.audit.AuditingAuthorization] and never affect authorization.
 */
fun interface AuditEventSink {
    fun publish(event: AuditEvent)
}
