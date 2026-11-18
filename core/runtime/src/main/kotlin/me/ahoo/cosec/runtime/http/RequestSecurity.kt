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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.engine.AbsentAttributes
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Memberships
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
@ExperimentalCoSecApi
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
    /** The application-facing view. */
    val securityContext: SecurityContext by lazy(LazyThreadSafetyMode.PUBLICATION) { PipelineSecurityContext(this) }

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
