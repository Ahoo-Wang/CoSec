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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.engine.AbsentAttributes
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.identity.Identity

/**
 * Who a permitted request is, and in what scope (RFC 0006 §7.6): the principal as authenticated (null when anonymous),
 * its memberships, and the scope `ScopeResolver` validated. The adapters expose it as [securityContext]; the
 * [AuthorizationChecker] evaluates further actions as it.
 */
class RequestSecurity internal constructor(
    val requestId: String,
    val principal: AuthenticatedPrincipal?,
    val scope: Scope,
    /** The principal's memberships for this request (RFC 0004 §4.5): the control plane analyzes grants with them. */
    val memberships: Memberships,
    /** The request the permit was decided for: the checker reuses its subject and environment. */
    internal val request: AuthzRequest,
    /** The host's attributes of that request (headers, request attributes): a check reads them as the request did. */
    internal val attributes: ConditionInput = AbsentAttributes,
    /** In a service behind a gateway (`cosec.pipeline.mode=gateway`), the identity the gateway forwarded. */
    val identity: Identity? = null,
) {
    /**
     * Set once the adapter that bound this request has ended it ([end]). A field, not an object: it fits in the padding
     * of this one, so a permitted request allocates nothing more for it. The forms [inApp] and [asPrincipal] make of
     * this request live within one check, which asks this request, not them (`AuthorizationChecker`).
     */
    @Volatile
    private var ended: Boolean = false

    /** The application-facing view. */
    val securityContext: SecurityContext by lazy(LazyThreadSafetyMode.PUBLICATION) { PipelineSecurityContext(this) }

    /**
     * Whether the request is still in progress: true until it is ended ([end]), by the adapter that bound it, or by the
     * transport of the application's own that closes its [Outcome]. A check of an ended request is refused (`PIP-26`).
     */
    internal val inProgress: Boolean get() = !ended

    /**
     * Admits a check of this request: false once it has ended (`PIP-26`); otherwise the host's headers the check reads
     * are copied first, so that it never reads the server's request, which the server may recycle once the response is
     * written (`PIP-32`). A check may run on a thread of the application's own while the adapter ends the request: the
     * copy and [end] hold one lock, so the copy is made before [end] returns, and the adapters end a request before its
     * server can recycle it. A request whose checks read no host header holds no lock.
     */
    internal fun admit(): Boolean {
        val host = attributes as? HttpAttributes ?: return !ended
        synchronized(host) {
            if (ended) return false
            host.freeze()
            return true
        }
    }

    /**
     * Ends the request: from now on no check permits as it (`PIP-26`). The adapters call it, when the request
     * completes: WebFlux and the gateway when the chain's `Mono` terminates, the servlet filter when the dispatch returns
     * or, for an async request, when it completes, fails or times out, before the server may recycle the request. A
     * transport of the application's own ends it through [Outcome.close], the one public way. Idempotent. Waits for a
     * check copying the headers ([admit]).
     */
    @InternalCoSecApi
    fun end() {
        val host = attributes as? HttpAttributes
        if (host == null) {
            ended = true
            return
        }
        synchronized(host) { ended = true }
    }

    /**
     * The same principal in the default space of [app] of its tenant, resolved as [ScopeResolver] resolves a host of
     * [app]: its credential must declare [app] (RFC 0012 §3.5). Null otherwise, and for an anonymous request.
     */
    internal fun inApp(app: AppId): RequestSecurity? {
        val authenticated = principal ?: return null
        val member = ScopeSubject(scope.tenant, true, memberships, authenticated.app?.value)
        val resolved = ScopeResolver.resolve(member, "", HostScope(app))
        val scope = (resolved as? ScopeResolution.Resolved)?.scope ?: return null
        return RequestSecurity(requestId, authenticated, scope, memberships, request, attributes)
    }

    /**
     * This request as [principal] with [memberships], in the default space of [app] of [principal]'s tenant; null when
     * that scope does not resolve. [principal] was recorded when it was permitted in [app], so what its credential
     * declared is not asked again. For checking again what a principal recorded earlier may do, such as the approver of a
     * control-plane change when it publishes (RFC 0007 §7.5): it reads the current documents, and the environment of
     * this request, not the one the principal had. Internal (round-2 review R3): a [RequestSecurity] is a permitted
     * request, which a route trusts, so nothing outside the pipeline mints one; [AuthorizationChecker.decideAs] is the
     * way in, and it hands back a decision only.
     */
    internal fun asPrincipal(
        principal: AuthenticatedPrincipal,
        memberships: Memberships,
        app: AppId
    ): RequestSecurity? {
        val member = ScopeSubject(principal.tenant, true, memberships, app.value)
        val resolved = ScopeResolver.resolve(member, "", HostScope(app))
        val scope = (resolved as? ScopeResolution.Resolved)?.scope ?: return null
        val subject = Subject(
            id = principal.id.value,
            authenticated = true,
            attributes = principal.attributes,
            principalBindings = principal.principalBindings,
            breakGlass = principal.breakGlass,
        )
        val request = with(request) { AuthzRequest(subject, action, resource, environment) }
        return RequestSecurity(requestId, principal, scope, memberships, request, attributes)
    }

    /**
     * What the engine reads to decide this request at [time]; with [app], [action] on [resource] asked by the same
     * principal in the default space of [app] ([inApp]), or null when that scope does not resolve. For the control
     * plane's lock-out check (UC-6), which decides the publisher's own way back against a candidate document with the
     * facts of this request: it hands out an engine request, never a [RequestSecurity], so nothing outside the pipeline
     * mints a permitted request. External attributes are not fetched: a statement that reads one decides as its
     * `onMissing` says.
     */
    @InternalCoSecApi
    fun engineRequest(
        time: Long,
        app: AppId? = null,
        action: Action? = null,
        resource: Resource? = null,
    ): EngineRequest? {
        // The control plane decides with these attributes after this returns: copied, or nothing once ended (PIP-32).
        if (!admit()) return null
        if (app == null) {
            return EngineRequest(request, scope, memberships, time, attributes)
        }
        val asked = inApp(app) ?: return null
        val subject = asked.request
        val authz =
            AuthzRequest(subject.subject, action ?: subject.action, resource ?: subject.resource, subject.environment)
        return EngineRequest(authz, asked.scope, memberships, time, attributes)
    }

    override fun toString(): String =
        "RequestSecurity(request=$requestId, principal=${principal?.id?.value}, tenant=${scope.tenant.value})"

    companion object {
        /**
         * The security of the request [context] is the [securityContext] of, as an adapter exposes it; null for a
         * context the pipeline did not build, which no route that needs a permitted request may trust.
         */
        fun of(context: SecurityContext?): RequestSecurity? = (context as? PipelineSecurityContext)?.security
    }
}
