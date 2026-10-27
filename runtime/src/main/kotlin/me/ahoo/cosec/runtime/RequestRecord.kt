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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.runtime.attribute.FetchedAttributes
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.OutcomeSummary
import me.ahoo.cosec.runtime.event.ReplayHandle
import me.ahoo.cosec.runtime.event.RequestSummary
import me.ahoo.cosec.runtime.event.SubjectSummary
import me.ahoo.cosec.runtime.event.TraceContext
import me.ahoo.cosec.runtime.http.HttpFacts

/**
 * What the pipeline learns about one request, for its [DecisionEvent] (RFC 0006 §9.2). Made only when listeners are
 * registered, filled as the stages run; whatever a stage did not reach stays null.
 */
internal class RequestRecord(val startNanos: Long, val time: Long, val trace: TraceContext?) {
    var path: String? = null
    var deviceId: String = ""
    var authn: AuthnResult? = null
    var scope: Scope? = null
    var action: Action? = null
    var resource: Resource? = null
    var view: SnapshotView? = null
    var request: FlatRequest? = null
    var fetched: FetchedAttributes? = null
    var decision: Decision? = null
    var breakGlass: Boolean = false
    var requiredFailures: List<String> = emptyList()

    fun authenticated(
        path: String,
        deviceId: String,
        authn: AuthnResult,
        action: Action,
        resource: Resource,
        fetched: FetchedAttributes?,
    ) {
        this.path = path
        this.deviceId = deviceId
        this.authn = authn
        this.action = action
        this.resource = resource
        this.fetched = fetched
    }

    fun resolved(scope: Scope, view: SnapshotView) {
        this.scope = scope
        this.view = view
    }

    fun event(
        id: String,
        requestId: String,
        facts: HttpFacts,
        outcome: Outcome?,
        replay: ReplayHandle? = null,
    ): DecisionEvent {
        val basis = outcome?.basis
        return DecisionEvent(
            id = id,
            requestId = requestId,
            time = time,
            elapsedNanos = System.nanoTime() - startNanos,
            kind = kind(basis),
            outcome = if (outcome == null) OutcomeSummary.CANCELLED else summary(outcome),
            decision = decision,
            breakGlass = breakGlass,
            snapshotVersion = view?.version,
            subject = subject(),
            scope = scope,
            action = action,
            resource = resource,
            request = RequestSummary(facts.method, path ?: facts.rawPath, facts.remoteAddress, deviceId),
            attributes = fetched?.statuses().orEmpty(),
            trace = trace,
            requiredFailures = requiredFailures,
            replay = replay,
        )
    }

    private fun subject(): SubjectSummary = when (val authn = authn) {
        is AuthnResult.Authenticated ->
            SubjectSummary(authn.principal.tenant.value, authn.principal.id.value, true, authn.method, null)
        is AuthnResult.Rejected -> anonymous(authn.reason)
        AuthnResult.Anonymous, is AuthnResult.Unavailable, null -> anonymous(null)
    }

    private fun anonymous(rejected: AuthnResult.RejectReason?) =
        SubjectSummary(TenantId.DEFAULT.value, CoSecPrincipal.ANONYMOUS_ID, false, null, rejected)

    private fun kind(basis: Basis?): DecisionEvent.Kind = when (basis) {
        null -> DecisionEvent.Kind.CANCELLED
        is Basis.InvalidRequest -> DecisionEvent.Kind.BAD_REQUEST
        is Basis.Error -> DecisionEvent.Kind.ERROR
        is Basis.Unavailable ->
            if (basis.reason is Unavailability.AuthenticatorUnavailable) {
                DecisionEvent.Kind.AUTHENTICATION_UNAVAILABLE
            } else {
                DecisionEvent.Kind.DECIDED
            }
        is Basis.Decided, is Basis.RateLimited -> DecisionEvent.Kind.DECIDED
    }

    private fun reason(decision: Decision): OutcomeSummary.Reason = when (decision) {
        is Decision.Permit -> OutcomeSummary.Reason.PERMIT
        is Decision.Deny -> when (decision.by) {
            null -> OutcomeSummary.Reason.IMPLICIT_DENY
            is StatementRef.ScopeRejected -> OutcomeSummary.Reason.SCOPE_REJECTED
            is StatementRef.Policy -> OutcomeSummary.Reason.EXPLICIT_DENY
            is StatementRef.Boundary -> OutcomeSummary.Reason.BOUNDARY
        }
        is Decision.Indeterminate -> OutcomeSummary.Reason.INDETERMINATE
    }

    private fun summary(outcome: Outcome): OutcomeSummary {
        val reason = when (val basis = outcome.basis) {
            is Basis.InvalidRequest -> OutcomeSummary.Reason.INVALID_REQUEST
            is Basis.Error -> OutcomeSummary.Reason.ERROR
            is Basis.Unavailable -> when (basis.reason) {
                is Unavailability.Indeterminate -> OutcomeSummary.Reason.INDETERMINATE
                is Unavailability.AuthenticatorUnavailable -> OutcomeSummary.Reason.AUTHENTICATOR_UNAVAILABLE
                Unavailability.DeadlineExceeded -> OutcomeSummary.Reason.DEADLINE_EXCEEDED
            }
            is Basis.Decided -> reason(basis.decision)
            is Basis.RateLimited -> OutcomeSummary.Reason.TOO_MANY_REQUESTS
        }
        val cause = when (val basis = outcome.basis) {
            is Basis.Unavailable -> (basis.reason as? Unavailability.Indeterminate)?.cause
            else -> null
        }
        return OutcomeSummary(outcome.status, outcome.proceed, outcome.problem?.type, reason, cause)
    }
}
