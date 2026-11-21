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
import me.ahoo.cosec.api.audit.AuditEventKind
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditVia
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.event.CauseName
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
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
        val (decision, reasonCode) = decision(event.outcome.reason, event.subject.rejected, event.outcome.cause)
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
            kind = kind(event.kind),
        )
    }

    /**
     * The audit decision and reason code of an event's [reason], with the credential [rejectedAs] and the [cause] of an
     * indeterminate one. One mapping from the event's reason (UD-7): exhaustive, so a new Reason without a value does
     * not compile.
     */
    internal fun decision(
        reason: Reason,
        rejectedAs: AuthnResult.RejectReason?,
        cause: Cause?,
    ): Pair<AuditDecision, AuditReasonCode> {
        val decision = decisionOf(reason)
        return decision to reasonCode(decision, rejected(rejectedAs), cause)
    }

    /** The event's reason in audit's words; the ways a request is undecided before the engine ran are INDETERMINATE. */
    private fun decisionOf(reason: Reason): AuditDecision = when (reason) {
        Reason.PERMIT -> AuditDecision.PERMIT
        Reason.TOO_MANY_REQUESTS -> AuditDecision.TOO_MANY_REQUESTS
        Reason.EXPLICIT_DENY -> AuditDecision.EXPLICIT_DENY
        Reason.IMPLICIT_DENY -> AuditDecision.IMPLICIT_DENY
        Reason.SCOPE_REJECTED -> AuditDecision.SCOPE_REJECTED
        Reason.BOUNDARY -> AuditDecision.BOUNDARY
        // A 400 is refused before any decision; kind says why.
        Reason.INVALID_REQUEST -> AuditDecision.INVALID_REQUEST
        Reason.INDETERMINATE, Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED, Reason.CANCELLED ->
            AuditDecision.INDETERMINATE
        Reason.ERROR -> AuditDecision.ERROR
    }

    /** The decision's own code, a rejected credential's for a deny, the rate limiter for an indeterminate permit. */
    private fun reasonCode(decision: AuditDecision, rejected: AuditReasonCode?, cause: Cause?): AuditReasonCode = when {
        rejected != null && decision in DENIES -> rejected
        decision == AuditDecision.INDETERMINATE && cause is Cause.RateLimiterUnavailable ->
            AuditReasonCode.RATE_LIMITER_UNAVAILABLE
        else -> OWN_CODE.getValue(decision)
    }

    /** The decisions a rejected credential's code replaces the decision's own in. */
    private val DENIES = setOf(
        AuditDecision.EXPLICIT_DENY,
        AuditDecision.IMPLICIT_DENY,
        AuditDecision.SCOPE_REJECTED,
        AuditDecision.BOUNDARY,
    )

    /** Each decision's own code, the same word (`AuditVocabularyTest` holds every one). */
    private val OWN_CODE: Map<AuditDecision, AuditReasonCode> = mapOf(
        AuditDecision.PERMIT to AuditReasonCode.PERMIT,
        AuditDecision.TOO_MANY_REQUESTS to AuditReasonCode.TOO_MANY_REQUESTS,
        AuditDecision.EXPLICIT_DENY to AuditReasonCode.EXPLICIT_DENY,
        AuditDecision.IMPLICIT_DENY to AuditReasonCode.IMPLICIT_DENY,
        AuditDecision.SCOPE_REJECTED to AuditReasonCode.SCOPE_REJECTED,
        AuditDecision.BOUNDARY to AuditReasonCode.BOUNDARY,
        AuditDecision.INVALID_REQUEST to AuditReasonCode.INVALID_REQUEST,
        AuditDecision.INDETERMINATE to AuditReasonCode.INDETERMINATE,
        AuditDecision.ERROR to AuditReasonCode.ERROR,
    )

    private fun rejected(reason: AuthnResult.RejectReason?): AuditReasonCode? = when (reason) {
        AuthnResult.RejectReason.TOKEN_EXPIRED -> AuditReasonCode.TOKEN_EXPIRED
        AuthnResult.RejectReason.TOKEN_REVOKED -> AuditReasonCode.TOKEN_REVOKED
        AuthnResult.RejectReason.TOKEN_INVALID, AuthnResult.RejectReason.API_KEY_INVALID -> AuditReasonCode.TOKEN_INVALID
        AuthnResult.RejectReason.TOKEN_NOT_YET_VALID -> AuditReasonCode.TOKEN_NOT_YET_VALID
        AuthnResult.RejectReason.DELEGATION_UNSUPPORTED -> AuditReasonCode.DELEGATION_UNSUPPORTED
        null -> null
    }

    private fun reason(event: DecisionEvent): String {
        // A refused credential is named in the reason, not as the principal, which stays the anonymous one (review L3).
        event.subject.refused?.let { return "${event.outcome.reason.name}: ${event.subject.rejected} of $it" }
        (event.by as? StatementRef.Boundary)?.let { return "${event.outcome.reason.name}: ${it.boundary.value}" }
        val cause = event.outcome.cause ?: return event.outcome.reason.name
        return "${event.outcome.reason.name}: ${CauseName.of(cause).wire}"
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

    private fun kind(kind: DecisionEvent.Kind): AuditEventKind = when (kind) {
        DecisionEvent.Kind.DECIDED -> AuditEventKind.DECIDED
        DecisionEvent.Kind.BAD_REQUEST -> AuditEventKind.BAD_REQUEST
        DecisionEvent.Kind.AUTHENTICATION_UNAVAILABLE -> AuditEventKind.AUTHENTICATION_UNAVAILABLE
        DecisionEvent.Kind.CANCELLED -> AuditEventKind.CANCELLED
        DecisionEvent.Kind.ERROR -> AuditEventKind.ERROR
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
