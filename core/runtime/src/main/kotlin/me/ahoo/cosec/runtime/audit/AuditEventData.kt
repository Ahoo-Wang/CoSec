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

package me.ahoo.cosec.runtime.audit

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditAuthorization
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditDevice
import me.ahoo.cosec.api.audit.AuditDocument
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditPrincipal
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.api.audit.AuditSnapshotVersion
import me.ahoo.cosec.api.audit.AuditSource
import me.ahoo.cosec.api.audit.AuditStatement
import me.ahoo.cosec.api.audit.AuditTrace
import me.ahoo.cosec.api.audit.AuditVia
import java.time.Instant

@InternalCoSecApi
class AuditPrincipalData(
    override val id: String,
    override val authenticated: Boolean,
    override val roles: Set<String>,
) : AuditPrincipal {

    /** These values with some replaced: for CoSec's own use. */
    @InternalCoSecApi
    @Suppress("LongParameterList")
    fun copy(
        id: String = this.id,
        authenticated: Boolean = this.authenticated,
        roles: Set<String> = this.roles,
    ): AuditPrincipalData = AuditPrincipalData(
        id = id,
        authenticated = authenticated,
        roles = roles,
    )
}

@InternalCoSecApi
class AuditDeviceData(
    override val id: String?,
) : AuditDevice

@InternalCoSecApi
class AuditRequestData(
    override val id: String?,
    override val appId: String?,
    override val spaceId: String?,
    override val remoteIp: String,
    override val method: String,
    override val path: String,
    override val routeId: String?,
    override val device: AuditDeviceData,
) : AuditRequest

@InternalCoSecApi
class AuditStatementData(
    override val index: Int,
    override val name: String,
) : AuditStatement

@InternalCoSecApi
class AuditSourceData(
    override val via: AuditVia,
    override val document: AuditDocument? = null,
    override val policy: String? = null,
    override val statement: AuditStatementData? = null,
) : AuditSource

@InternalCoSecApi
class AuditAuthorizationData(
    override val decision: AuditDecision,
    override val reasonCode: AuditReasonCode,
    override val reason: String,
    override val elapsedNanos: Long,
    override val source: AuditSourceData,
) : AuditAuthorization

@InternalCoSecApi
class AuditTraceData(
    override val traceId: String,
    override val spanId: String,
) : AuditTrace

@InternalCoSecApi
class AuditSnapshotVersionData(
    override val platform: Long,
    override val app: Long?,
    override val tenant: Long?,
) : AuditSnapshotVersion

@InternalCoSecApi
class AuditEventData(
    override val eventId: String,
    override val timestamp: Instant,
    override val tenantId: String,
    override val principal: AuditPrincipalData,
    override val request: AuditRequestData,
    override val authorization: AuditAuthorizationData,
    override val trace: AuditTraceData?,
    override val snapshotVersion: AuditSnapshotVersionData? = null,
    override val binding: String? = null,
    override val breakGlass: Boolean = false,
    override val kind: String = "DECIDED",
) : AuditEvent {

    /** These values with some replaced: for CoSec's own use. */
    @InternalCoSecApi
    @Suppress("LongParameterList")
    fun copy(
        eventId: String = this.eventId,
        timestamp: Instant = this.timestamp,
        tenantId: String = this.tenantId,
        principal: AuditPrincipalData = this.principal,
        request: AuditRequestData = this.request,
        authorization: AuditAuthorizationData = this.authorization,
        trace: AuditTraceData? = this.trace,
        snapshotVersion: AuditSnapshotVersionData? = this.snapshotVersion,
        binding: String? = this.binding,
        breakGlass: Boolean = this.breakGlass,
        kind: String = this.kind,
    ): AuditEventData = AuditEventData(
        eventId = eventId,
        timestamp = timestamp,
        tenantId = tenantId,
        principal = principal,
        request = request,
        authorization = authorization,
        trace = trace,
        snapshotVersion = snapshotVersion,
        binding = binding,
        breakGlass = breakGlass,
        kind = kind,
    )
}
