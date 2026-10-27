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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.MdcContextContributor
import me.ahoo.cosec.runtime.event.TraceContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/** RFC 0006 §9.5.3: the audit record keeps the detailed reason, the binding, the trace and the snapshot version. */
class AuditEventsTest {
    /** Maps every event the REQUIRED listener sees, so the event is the one before any best-effort copy. */
    private class Capture : DecisionListener {
        override val name = "capture"
        override val delivery = Delivery.REQUIRED
        var event: DecisionEvent? = null

        override suspend fun onDecision(event: DecisionEvent) {
            this.event = event
        }
    }

    private fun audit(
        decision: Decision,
        authenticator: Authenticator = authenticator(authenticated()),
        path: String = "/orders",
    ): AuditEvent {
        val capture = Capture()
        val listeners = DecisionListeners(listOf(capture))
        try {
            runBlocking(TraceContext("trace-1", "span-1", true)) {
                PipelineFixtures.pipeline(authenticator, StubEngine(decision)) { it.copy(listeners = listeners) }
                    .evaluate(facts(path))
            }
        } finally {
            listeners.cancel()
        }
        return AuditEvents.from(capture.event!!)
    }

    @Test
    fun aPermitThroughABinding() {
        val bound = StatementRef.Policy(
            PolicyId("orders"),
            1,
            "read",
            BindingRef.Bound(BindingId("g:orders"), DocumentKind.TENANT)
        )
        val event = audit(Decision.Permit(bound))
        event.authorization.decision.assert().isEqualTo(AuditDecision.ALLOW)
        event.authorization.reasonCode.assert().isEqualTo(AuditReasonCode.ALLOW)
        event.authorization.source.type.assert().isEqualTo(AuditSourceType.GLOBAL_POLICY)
        event.authorization.source.policy!!.statement.name.assert().isEqualTo("read")
        event.binding.assert().isEqualTo("g:orders")
        event.trace!!.spanId.assert().isEqualTo("span-1")
        event.tenantId.assert().isEqualTo(PipelineFixtures.TENANT.value)
        event.principal.id.assert().isEqualTo(PipelineFixtures.PRINCIPAL.value)
        event.kind.assert().isEqualTo("DECIDED")
        event.breakGlass.assert().isFalse()
    }

    @Test
    fun deniesKeepExplicitImplicitAndScopeApart() {
        audit(PipelineFixtures.EXPLICIT_DENY).authorization.let {
            it.decision.assert().isEqualTo(AuditDecision.EXPLICIT_DENY)
            it.source.type.assert().isEqualTo(AuditSourceType.PRINCIPAL_POLICY)
        }
        audit(Decision.Deny(null)).authorization.reasonCode.assert().isEqualTo(AuditReasonCode.IMPLICIT_DENY)
        audit(Decision.Deny(null)).authorization.source.type.assert().isEqualTo(AuditSourceType.NONE)
        audit(Decision.Deny(StatementRef.ScopeRejected(ScopeResolution.Rejected.Reason.APP_MISMATCH)))
            .authorization.reasonCode.assert().isEqualTo(AuditReasonCode.SCOPE_REJECTED)
        val revoked = authenticator(AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_REVOKED))
        audit(Decision.Deny(null), revoked).authorization.reasonCode.assert().isEqualTo(AuditReasonCode.TOKEN_REVOKED)
        val expired = authenticator(AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_EXPIRED))
        audit(Decision.Deny(null), expired).authorization.reasonCode.assert().isEqualTo(AuditReasonCode.TOKEN_EXPIRED)
        val invalid = authenticator(AuthnResult.Rejected(AuthnResult.RejectReason.API_KEY_INVALID))
        audit(Decision.Deny(null), invalid).authorization.reasonCode.assert().isEqualTo(AuditReasonCode.TOKEN_INVALID)
    }

    @Test
    fun builtinRootAndRoleSources() {
        fun source(policy: String, via: BindingRef) =
            audit(Decision.Permit(StatementRef.Policy(PolicyId(policy), 0, "s", via))).authorization.source.type
        source("cosec:blacklist", BindingRef.Builtin).assert().isEqualTo(AuditSourceType.BLACKLIST)
        source("cosec:root", BindingRef.Bound(BindingId("root"), DocumentKind.PLATFORM)).assert()
            .isEqualTo(AuditSourceType.ROOT)
        source("perm:order/read", BindingRef.Bound(BindingId("r"), DocumentKind.APP)).assert()
            .isEqualTo(AuditSourceType.ROLE_PERMISSION)
    }

    @Test
    fun indeterminateAndOtherKinds() {
        val unavailable = audit(
            PipelineFixtures.PERMIT,
            Authenticator { AuthnResult.Unavailable(IllegalStateException()) }
        )
        unavailable.authorization.decision.assert().isEqualTo(AuditDecision.INDETERMINATE)
        unavailable.kind.assert().isEqualTo("AUTHENTICATION_UNAVAILABLE")
        val badRequest = audit(PipelineFixtures.PERMIT, path = "/a/%2e%2e/b")
        badRequest.kind.assert().isEqualTo("BAD_REQUEST")
        badRequest.authorization.decision.assert().isEqualTo(AuditDecision.EXPLICIT_DENY)
    }

    @Test
    fun theMdcContributorRestoresTheBridgingThreadsMdc() = runBlocking<Unit> {
        MDC.put("tenant", "acme")
        try {
            val context = BridgeContextContributor.capture(listOf(MdcContextContributor))
            MDC.clear()
            withContext(Dispatchers.Default + context) { MDC.get("tenant") }.assert().isEqualTo("acme")
        } finally {
            MDC.clear()
        }
    }
}
