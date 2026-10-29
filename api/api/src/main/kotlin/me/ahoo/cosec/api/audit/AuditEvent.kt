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

    /** No decision could be made: 503 (RFC 0006 §6.1, §9.5.3). */
    INDETERMINATE,
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

    /** RFC 0006 §9.5.3. */
    INDETERMINATE,
    SCOPE_REJECTED,
    RATE_LIMITER_UNAVAILABLE,
    TOKEN_REVOKED,

    /** A Permit outside the tenant's platform boundary (RFC 0007 §7.6); the response says only `forbidden`. */
    BOUNDARY,

    /** A token that delegates (an `act` claim), which 8.0 refuses (RFC 0009 §6); the response says `token-invalid`. */
    DELEGATION_UNSUPPORTED,
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

interface AuditPrincipal {
    val id: String
    val authenticated: Boolean
    val roles: Set<String>
    val policies: Set<String>
}

interface AuditDevice {
    val id: String?
    val userAgent: String?
}

interface AuditRequest {
    val id: String?
    val appId: String?
    val spaceId: String?
    val remoteIp: String
    val method: String
    val path: String
    val routeId: String?
    val device: AuditDevice

    companion object {
        const val ROUTE_ID_ATTRIBUTE_KEY = "cosec.audit.routeId"
    }
}

interface AuditStatement {
    val index: Int
    val name: String
}

interface AuditPolicy {
    val id: String
    val type: PolicyType
    val statement: AuditStatement
}

interface AuditPermission {
    val id: String
    val name: String
}

interface AuditRole {
    val id: String
    val permission: AuditPermission
}

interface AuditSource {
    val type: AuditSourceType
    val policy: AuditPolicy?
    val role: AuditRole?
}

interface AuditAuthorization {
    val decision: AuditDecision
    val reasonCode: AuditReasonCode
    val reason: String
    val elapsedNanos: Long
    val source: AuditSource
}

interface AuditTrace {
    val traceId: String
    val spanId: String

    companion object {
        const val TRACE_ID_ATTRIBUTE_KEY = "cosec.audit.traceId"
        const val SPAN_ID_ATTRIBUTE_KEY = "cosec.audit.spanId"
    }
}

/** The revisions of the snapshot documents a decision read (RFC 0004 §7.2): app and tenant are null when not read. */
interface AuditSnapshotVersion {
    val platform: Long
    val app: Long?
    val tenant: Long?
}

interface AuditEvent {
    val eventId: String
    val timestamp: Instant
    val tenantId: String
    val principal: AuditPrincipal
    val request: AuditRequest
    val authorization: AuditAuthorization
    val trace: AuditTrace?

    /** The documents the decision read; null when it read none (RFC 0006 §9.5.3). */
    val snapshotVersion: AuditSnapshotVersion? get() = null

    /** The binding the deciding statement applied through, or `attached:<policy>`; null when none. */
    val binding: String? get() = null

    /** Whether the request was permitted through a break-glass binding. */
    val breakGlass: Boolean get() = false

    /** What the event records: `DECIDED`, `BAD_REQUEST`, `AUTHENTICATION_UNAVAILABLE`, `CANCELLED` or `ERROR`. */
    val kind: String get() = "DECIDED"
}
