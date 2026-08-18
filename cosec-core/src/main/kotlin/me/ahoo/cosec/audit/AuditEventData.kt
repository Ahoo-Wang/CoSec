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

import me.ahoo.cosec.api.audit.AuditAuthorization
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditDevice
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditPermission
import me.ahoo.cosec.api.audit.AuditPolicy
import me.ahoo.cosec.api.audit.AuditPrincipal
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.api.audit.AuditRole
import me.ahoo.cosec.api.audit.AuditSource
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.audit.AuditStatement
import me.ahoo.cosec.api.audit.AuditTrace
import me.ahoo.cosec.api.policy.PolicyType
import java.time.Instant

data class AuditPrincipalData(
    override val id: String,
    override val authenticated: Boolean,
    override val roles: Set<String>,
    override val policies: Set<String>,
) : AuditPrincipal

data class AuditDeviceData(
    override val id: String?,
    override val userAgent: String?,
) : AuditDevice

data class AuditRequestData(
    override val id: String?,
    override val appId: String?,
    override val spaceId: String?,
    override val remoteIp: String,
    override val method: String,
    override val path: String,
    override val routeId: String?,
    override val device: AuditDeviceData,
) : AuditRequest

data class AuditStatementData(
    override val index: Int,
    override val name: String,
) : AuditStatement

data class AuditPolicyData(
    override val id: String,
    override val type: PolicyType,
    override val statement: AuditStatementData,
) : AuditPolicy

data class AuditPermissionData(
    override val id: String,
    override val name: String,
) : AuditPermission

data class AuditRoleData(
    override val id: String,
    override val permission: AuditPermissionData,
) : AuditRole

data class AuditSourceData(
    override val type: AuditSourceType,
    override val policy: AuditPolicyData? = null,
    override val role: AuditRoleData? = null,
) : AuditSource

data class AuditAuthorizationData(
    override val decision: AuditDecision,
    override val reasonCode: AuditReasonCode,
    override val reason: String,
    override val elapsedNanos: Long,
    override val source: AuditSourceData,
) : AuditAuthorization

data class AuditTraceData(
    override val traceId: String,
    override val spanId: String,
) : AuditTrace

data class AuditEventData(
    override val eventId: String,
    override val timestamp: Instant,
    override val tenantId: String,
    override val principal: AuditPrincipalData,
    override val request: AuditRequestData,
    override val authorization: AuditAuthorizationData,
    override val trace: AuditTraceData?,
) : AuditEvent
