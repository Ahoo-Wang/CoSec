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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.trace.EvaluationTrace
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.runtime.attribute.FetchedAttributes
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.ReplayHandle
import me.ahoo.cosec.runtime.event.RequestSummary
import me.ahoo.cosec.runtime.event.SubjectSummary
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.http.TraceContext
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.ratelimit.RateLimitResult

/**
 * One request on its way through the stages (RFC 0013 §4.2), and what its [DecisionEvent] says (RFC 0006 §9.2). Each
 * field is written once, by the stage that owns it, except [authn] and [fetched], which `ResolveMembers` writes again
 * when the membership provider refuses the credential (`RequestStateWriterTest` holds each setter to its stage).
 *
 * What decides is `lateinit`: read before its stage ran, it throws, so no stage decides on a value that is not there.
 * Nullable only where null is an answer: [trace] (no trace), [evaluationTrace] (tracing off), [tightest] (no rate limit
 * counted), [security] (not a permit). [principal] and [tenant] are no fields: they follow [authn], so a refused
 * credential is anonymous everywhere at once. A pipeline without attribute providers fetches into
 * [FetchedAttributes.NONE], never null.
 */
internal class RequestState(
    /** The request as the adapter read it. */
    val input: HttpFacts,
    val requestId: String,
    val trace: TraceContext?,
    val startNanos: Long,
    /** When the request started, in epoch milliseconds: the event's `time`. */
    val time: Long,
) {
    val traceId: String? get() = trace?.traceId

    /** [input] with the gateway route's app as the host app (RC1-6). */
    lateinit var facts: HttpFacts
    lateinit var path: String
    lateinit var parameters: CoSecParameters
    lateinit var action: Action
    lateinit var resource: Resource
    lateinit var authn: AuthnResult
    lateinit var fetched: FetchedAttributes
    lateinit var subject: Subject
    lateinit var memberships: Memberships
    lateinit var scope: Scope
    lateinit var blacklist: Blacklist
    lateinit var view: SnapshotView

    /** The engine's request, with the external values stage B fetched. */
    lateinit var request: EngineRequest
    lateinit var decision: Decision
    var evaluationTrace: EvaluationTrace? = null
    var breakGlass: Boolean = false
    var tightest: RateLimitResult? = null
    var security: RequestSecurity? = null
    var requiredFailures: List<String> = emptyList()

    /** Who the credential proved; null when anonymous, rejected, or refused by the membership provider. */
    val principal: AuthenticatedPrincipal? get() = (authn as? AuthnResult.Authenticated)?.principal

    /** The principal's tenant, the default one when anonymous: the scope's from `ResolveScope` on. */
    val tenant: TenantId get() = principal?.tenant ?: TenantId.DEFAULT

    /**
     * The [DecisionEvent] for [outcome], null when the caller went away. A stage that did not finish leaves its fields
     * out, as it found them: what the request read before authenticating ([path], [action]) is named only once it was
     * authenticated, and its scope only with the view loaded for it.
     */
    fun event(id: String, outcome: Outcome?, replay: ReplayHandle? = null): DecisionEvent {
        val authenticated = ::authn.isInitialized
        val loaded = ::view.isInitialized
        return DecisionEvent(
            id = id,
            requestId = requestId,
            time = time,
            elapsedNanos = System.nanoTime() - startNanos,
            kind = DecisionEvents.kind(outcome?.basis),
            outcome = DecisionEvents.summary(outcome),
            decision = if (::decision.isInitialized) decision else null,
            evaluationTrace = evaluationTrace,
            breakGlass = breakGlass,
            snapshotVersion = if (loaded) view.version else null,
            subject = subject(),
            scope = if (loaded) scope else null,
            action = if (authenticated) action else null,
            resource = if (authenticated) resource else null,
            request = RequestSummary(
                input.method,
                if (authenticated) path else input.rawPath,
                input.remoteAddress,
                if (authenticated) parameters.deviceId else "",
                input.requestAttributes[AuditRequest.ROUTE_ID_ATTRIBUTE_KEY],
            ),
            attributes = if (::fetched.isInitialized) fetched.statuses() else emptyMap(),
            trace = trace,
            requiredFailures = requiredFailures,
            replay = replay,
        )
    }

    /** Whether the engine decided: only then is there a [request] and [view] a shadow listener can replay (§9.5.4). */
    val decided: Boolean get() = ::decision.isInitialized

    private fun subject(): SubjectSummary = when (val authn = if (::authn.isInitialized) authn else null) {
        is AuthnResult.Authenticated ->
            SubjectSummary(authn.principal.tenant.value, authn.principal.id.value, true, authn.method, null)
        is AuthnResult.Rejected -> anonymous(authn.reason, authn.refused)
        AuthnResult.Anonymous, is AuthnResult.Unavailable, null -> anonymous(null, null)
    }

    private fun anonymous(rejected: AuthnResult.RejectReason?, refused: AuthnResult.RefusedCredential?) =
        SubjectSummary(TenantId.DEFAULT.value, CoSecPrincipal.ANONYMOUS_ID, false, null, rejected, refused)
}
