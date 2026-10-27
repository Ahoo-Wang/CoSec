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

import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.audit.AuditAuthorizationData
import me.ahoo.cosec.audit.AuditDeviceData
import me.ahoo.cosec.audit.AuditEventData
import me.ahoo.cosec.audit.AuditPolicyData
import me.ahoo.cosec.audit.AuditPrincipalData
import me.ahoo.cosec.audit.AuditRequestData
import me.ahoo.cosec.audit.AuditSnapshotVersionData
import me.ahoo.cosec.audit.AuditSourceData
import me.ahoo.cosec.audit.AuditStatementData
import me.ahoo.cosec.audit.AuditTraceData
import me.ahoo.cosec.audit.LoggingAuditEventSink
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import java.time.Instant

/**
 * Maps a [DecisionEvent] to the audit record of `cosec-api` (RFC 0006 §9.5.3). The audit keeps the detailed reason the
 * response body does not: explicit and implicit denies apart, token rejections by kind, the cause of a 503.
 *
 * The source types stay those of 7.x until RFC 0004 X1 replaces them at the switch: a binding of the platform or a
 * tenant document is a global policy, an attachment a principal policy, a role binding of an app document a role
 * permission, the builtin rule the blacklist.
 */
object AuditEvents {
    fun from(event: DecisionEvent): AuditEvent {
        val (decision, reasonCode) = decision(event)
        val scope = event.scope
        val by = event.by as? StatementRef.Policy
        return AuditEventData(
            eventId = event.id,
            timestamp = Instant.ofEpochMilli(event.time),
            tenantId = event.subject.tenant,
            principal = AuditPrincipalData(
                id = event.subject.id,
                authenticated = event.subject.authenticated,
                roles = scope?.roles.orEmpty().mapTo(LinkedHashSet()) { it.value },
                policies = emptySet(),
            ),
            request = AuditRequestData(
                id = event.requestId,
                appId = scope?.app?.value,
                spaceId = scope?.space?.value,
                remoteIp = event.request.remoteIp,
                method = event.request.method,
                path = event.request.path,
                routeId = null,
                device = AuditDeviceData(event.request.deviceId.ifEmpty { null }, null),
            ),
            authorization = AuditAuthorizationData(
                decision = decision,
                reasonCode = reasonCode,
                reason = reason(event),
                elapsedNanos = event.elapsedNanos,
                source = source(by),
            ),
            // The parent span: the cosec.authorize span is made afterwards, from the event (RFC 0006 X3).
            trace = event.trace?.let { AuditTraceData(it.traceId, it.spanId) },
            snapshotVersion = event.snapshotVersion?.let { AuditSnapshotVersionData(it.platform, it.app, it.tenant) },
            binding = when (val via = by?.via) {
                is BindingRef.Bound -> via.id.value
                is BindingRef.Attached -> "attached:${via.policy.value}"
                BindingRef.Builtin -> "builtin"
                null -> null
            },
            breakGlass = event.breakGlass,
            kind = event.kind.name,
        )
    }

    private fun decision(event: DecisionEvent): Pair<AuditDecision, AuditReasonCode> {
        val rejected = rejected(event.subject.rejected)
        return when (event.outcome.reason) {
            Reason.PERMIT -> AuditDecision.ALLOW to AuditReasonCode.ALLOW
            Reason.EXPLICIT_DENY -> AuditDecision.EXPLICIT_DENY to (rejected ?: AuditReasonCode.EXPLICIT_DENY)
            Reason.IMPLICIT_DENY -> AuditDecision.IMPLICIT_DENY to (rejected ?: AuditReasonCode.IMPLICIT_DENY)
            Reason.SCOPE_REJECTED -> AuditDecision.EXPLICIT_DENY to (rejected ?: AuditReasonCode.SCOPE_REJECTED)
            // A 400 is refused before any decision; kind says why.
            Reason.INVALID_REQUEST -> AuditDecision.EXPLICIT_DENY to AuditReasonCode.EXPLICIT_DENY
            Reason.INDETERMINATE, Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED, Reason.CANCELLED ->
                AuditDecision.INDETERMINATE to AuditReasonCode.INDETERMINATE
            Reason.ERROR -> AuditDecision.ERROR to AuditReasonCode.ERROR
        }
    }

    private fun rejected(reason: AuthnResult.RejectReason?): AuditReasonCode? = when (reason) {
        AuthnResult.RejectReason.TOKEN_EXPIRED -> AuditReasonCode.TOKEN_EXPIRED
        AuthnResult.RejectReason.TOKEN_REVOKED -> AuditReasonCode.TOKEN_REVOKED
        AuthnResult.RejectReason.TOKEN_INVALID, AuthnResult.RejectReason.API_KEY_INVALID -> AuditReasonCode.TOKEN_INVALID
        null -> null
    }

    private fun reason(event: DecisionEvent): String {
        val cause = event.outcome.cause ?: return event.outcome.reason.name
        return "${event.outcome.reason.name}: ${cause::class.simpleName}"
    }

    private fun source(by: StatementRef.Policy?): AuditSourceData {
        by ?: return AuditSourceData(AuditSourceType.NONE)
        val (type, policyType) = when (val via = by.via) {
            BindingRef.Builtin -> AuditSourceType.BLACKLIST to PolicyType.SYSTEM
            is BindingRef.Attached -> AuditSourceType.PRINCIPAL_POLICY to PolicyType.CUSTOM
            is BindingRef.Bound -> when {
                by.policy == BuiltinPolicies.ROOT -> AuditSourceType.ROOT to PolicyType.SYSTEM
                via.document == DocumentKind.APP -> AuditSourceType.ROLE_PERMISSION to PolicyType.CUSTOM
                else -> AuditSourceType.GLOBAL_POLICY to PolicyType.GLOBAL
            }
        }
        return AuditSourceData(
            type,
            AuditPolicyData(by.policy.value, policyType, AuditStatementData(by.index, by.name))
        )
    }
}

/**
 * A [DecisionListener] over an [AuditEventSink] (RFC 0006 §9.5.3): maps each event with [AuditEvents.from], so a sink
 * written for 7.x needs no mapping of its own.
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
