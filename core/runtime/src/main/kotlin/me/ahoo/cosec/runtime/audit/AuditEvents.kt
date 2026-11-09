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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.audit

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditDocument
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditVia
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import me.ahoo.cosec.spi.audit.AuditEventSink
import me.ahoo.cosec.spi.authn.AuthnResult
import java.time.Instant

/**
 * Maps a [DecisionEvent] to the audit record of `cosec-api` (RFC 0006 §9.5.3). The audit keeps the detailed reason the
 * response body does not: explicit and implicit denies apart, token rejections by kind, the cause of a 503.
 *
 * The record names what decided in its own terms, so a consumer needs none of the engine's types: how the deciding
 * statement applied ([AuditVia]), the document that holds its binding or boundary ([AuditDocument]), the policy and the
 * statement.
 */
@InternalCoSecApi
object AuditEvents {
    fun from(event: DecisionEvent): AuditEvent {
        val (decision, reasonCode) = decision(event)
        val scope = event.scope
        return AuditEventData(
            eventId = event.id,
            timestamp = Instant.ofEpochMilli(event.time),
            tenantId = event.subject.tenant,
            principal = AuditPrincipalData(
                id = event.subject.id,
                authenticated = event.subject.authenticated,
                roles = scope?.roles.orEmpty().mapTo(LinkedHashSet()) { it.value },
            ),
            request = AuditRequestData(
                id = event.requestId,
                appId = scope?.app?.value,
                spaceId = scope?.space?.value,
                remoteIp = event.request.remoteIp,
                method = event.request.method,
                path = event.request.path,
                routeId = event.request.routeId,
                device = AuditDeviceData(event.request.deviceId.ifEmpty { null }),
            ),
            authorization = AuditAuthorizationData(
                decision = decision,
                reasonCode = reasonCode,
                reason = reason(event),
                elapsedNanos = event.elapsedNanos,
                source = source(event.by),
            ),
            // The parent span: the cosec.authorize span is made afterwards, from the event (RFC 0006 X3).
            trace = event.trace?.let { AuditTraceData(it.traceId, it.spanId) },
            snapshotVersion = event.snapshotVersion?.let { AuditSnapshotVersionData(it.platform, it.app, it.tenant) },
            binding = binding(event.by),
            breakGlass = event.breakGlass,
            kind = event.kind.name,
        )
    }

    private fun decision(event: DecisionEvent): Pair<AuditDecision, AuditReasonCode> {
        val rejected = rejected(event.subject.rejected)
        return when (event.outcome.reason) {
            Reason.PERMIT -> AuditDecision.ALLOW to AuditReasonCode.ALLOW
            Reason.TOO_MANY_REQUESTS -> AuditDecision.TOO_MANY_REQUESTS to AuditReasonCode.TOO_MANY_REQUESTS
            Reason.EXPLICIT_DENY, Reason.SCOPE_REJECTED, Reason.BOUNDARY ->
                AuditDecision.EXPLICIT_DENY to (rejected ?: explicit(event.outcome.reason))
            Reason.IMPLICIT_DENY -> AuditDecision.IMPLICIT_DENY to (rejected ?: AuditReasonCode.IMPLICIT_DENY)
            // A 400 is refused before any decision; kind says why.
            Reason.INVALID_REQUEST -> AuditDecision.EXPLICIT_DENY to AuditReasonCode.EXPLICIT_DENY
            Reason.INDETERMINATE ->
                if (event.outcome.cause is Cause.RateLimiterUnavailable) {
                    AuditDecision.INDETERMINATE to AuditReasonCode.RATE_LIMITER_UNAVAILABLE
                } else {
                    AuditDecision.INDETERMINATE to AuditReasonCode.INDETERMINATE
                }
            Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED, Reason.CANCELLED ->
                AuditDecision.INDETERMINATE to AuditReasonCode.INDETERMINATE
            Reason.ERROR -> AuditDecision.ERROR to AuditReasonCode.ERROR
        }
    }

    /** Why an explicit deny: a statement, the scope, or the tenant's boundary (RFC 0007 §7.6). */
    private fun explicit(reason: Reason): AuditReasonCode = when (reason) {
        Reason.SCOPE_REJECTED -> AuditReasonCode.SCOPE_REJECTED
        Reason.BOUNDARY -> AuditReasonCode.BOUNDARY
        else -> AuditReasonCode.EXPLICIT_DENY
    }

    private fun rejected(reason: AuthnResult.RejectReason?): AuditReasonCode? = when (reason) {
        AuthnResult.RejectReason.TOKEN_EXPIRED -> AuditReasonCode.TOKEN_EXPIRED
        AuthnResult.RejectReason.TOKEN_REVOKED -> AuditReasonCode.TOKEN_REVOKED
        AuthnResult.RejectReason.TOKEN_INVALID, AuthnResult.RejectReason.API_KEY_INVALID -> AuditReasonCode.TOKEN_INVALID
        AuthnResult.RejectReason.DELEGATION_UNSUPPORTED -> AuditReasonCode.DELEGATION_UNSUPPORTED
        null -> null
    }

    private fun reason(event: DecisionEvent): String {
        // A refused credential is named in the reason, not as the principal, which stays the anonymous one (review L3).
        event.subject.refused?.let { return "${event.outcome.reason.name}: ${event.subject.rejected} of $it" }
        (event.by as? StatementRef.Boundary)?.let { return "${event.outcome.reason.name}: ${it.boundary.value}" }
        val cause = event.outcome.cause ?: return event.outcome.reason.name
        return "${event.outcome.reason.name}: ${cause::class.simpleName}"
    }

    private fun source(by: StatementRef?): AuditSourceData = when (by) {
        is StatementRef.Policy -> AuditSourceData(
            via = via(by.via),
            document = when (val via = by.via) {
                is BindingRef.Bound -> document(via.document)
                is BindingRef.Boundary -> AuditDocument.PLATFORM
                is BindingRef.Attached, BindingRef.Builtin -> null
            },
            policy = by.policy.value,
            statement = AuditStatementData(by.index, by.name),
        )
        // The boundary refused because none of its statements matched, so there is no statement to name.
        is StatementRef.Boundary -> AuditSourceData(AuditVia.BOUNDARY, AuditDocument.PLATFORM, by.boundary.value)
        // No statement decided: a rejected scope is in reasonCode.
        is StatementRef.ScopeRejected, null -> AuditSourceData(AuditVia.NONE)
    }

    private fun via(via: BindingRef): AuditVia = when (via) {
        is BindingRef.Bound -> AuditVia.BINDING
        is BindingRef.Attached -> AuditVia.ATTACHMENT
        BindingRef.Builtin -> AuditVia.BUILTIN
        is BindingRef.Boundary -> AuditVia.BOUNDARY
    }

    private fun document(kind: DocumentKind): AuditDocument = when (kind) {
        DocumentKind.PLATFORM -> AuditDocument.PLATFORM
        DocumentKind.APP -> AuditDocument.APP
        DocumentKind.TENANT -> AuditDocument.TENANT
    }

    private fun binding(by: StatementRef?): String? = when (by) {
        is StatementRef.Policy -> when (val via = by.via) {
            is BindingRef.Bound -> via.id.value
            is BindingRef.Attached -> "attached:${via.policy.value}"
            BindingRef.Builtin -> "builtin"
            is BindingRef.Boundary -> "boundary:${via.tenant.value}"
        }
        is StatementRef.Boundary -> "boundary:${by.tenant.value}"
        is StatementRef.ScopeRejected, null -> null
    }
}

/**
 * A [DecisionListener] over an [AuditEventSink] (RFC 0006 §9.5.3): maps each event with [AuditEvents.from], so a sink
 * receives the stable audit record and needs no knowledge of the engine's types.
 *
 * Always BEST_EFFORT: `AuditEventSink.publish` returns before anything is written and reports no failure, so it can
 * never acknowledge a write. As a REQUIRED listener it would always "succeed", and a break-glass permit would proceed
 * with no audit record (§9.4.1, §9.4.3). A REQUIRED audit awaits its store, as `KafkaAuditListener` does, or is a
 * [DecisionListener] of its own.
 */
class AuditListener private constructor(
    override val name: String,
    private val sink: AuditEventSink,
) : DecisionListener {
    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    override suspend fun onDecision(event: DecisionEvent) {
        sink.publish(AuditEvents.from(event))
    }

    override fun toString(): String = "AuditListener($name)"

    companion object {
        fun of(sink: AuditEventSink, name: String): AuditListener = AuditListener(name, sink)
    }
}

/**
 * The audit log (RFC 0006 §9.5.3): what `LoggingAuditEventSink` writes, as a listener. BEST_EFFORT only: an asynchronous
 * appender drops silently, so a REQUIRED log would be a false guarantee (§9.4.1).
 */
class LoggingAuditListener(private val sink: LoggingAuditEventSink = LoggingAuditEventSink()) : DecisionListener {
    override val name: String get() = NAME

    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    override suspend fun onDecision(event: DecisionEvent) {
        sink.publish(AuditEvents.from(event))
    }

    companion object {
        const val NAME = "logging-audit"
    }
}
