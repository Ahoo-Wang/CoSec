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

import java.time.Instant

/**
 * Decision outcome recorded in an [AuditEvent].
 */
enum class AuditDecision {
    ALLOW,
    EXPLICIT_DENY,
    IMPLICIT_DENY,
    TOO_MANY_REQUESTS,
    ERROR,
}

/**
 * Structured audit event for a single authorization decision.
 */
data class AuditEvent(
    val timestamp: Instant,
    val tenantId: String,
    val principalId: String,
    val authenticated: Boolean,
    val roles: Set<String>,
    val policies: Set<String>,
    val appId: String?,
    val spaceId: String?,
    val deviceId: String?,
    val requestId: String?,
    val remoteIp: String,
    val method: String,
    val path: String,
    val decision: AuditDecision,
    val reason: String,
    val elapsedNanos: Long,
    val matchedPolicyId: String?,
    val matchedStatementName: String?,
    val matchedRoleId: String?,
    val matchedPermissionId: String?,
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
