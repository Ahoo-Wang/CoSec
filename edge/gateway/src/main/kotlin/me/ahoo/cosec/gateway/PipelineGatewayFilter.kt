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

package me.ahoo.cosec.gateway

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.webflux.HttpFactsReader
import me.ahoo.cosec.webflux.ReactivePipelineBridge
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The gateway filter over the coroutine pipeline (RFC 0006 §7.3): the same bridge as the WebFlux filter, so the two
 * answer identically (`WebAdapterParityTest`). The route id goes into the request attributes, and a permitted request
 * goes downstream with its `CoSec-Request-Id` and the headers the outcome forwards ([Outcome.forward]): the verified
 * `CoSec-Identity`, or none. A `CoSec-Identity` the client sent never reaches the pipeline nor a backend.
 *
 * Per route ([Route.getMetadata]):
 * - [APP_METADATA] is the host app of the route's requests (RFC 0004 §4.6, RC1-6), ahead of [host]: a client declares
 *   its app only where neither names one. It goes to the pipeline as configured ([HttpFacts.routeApp]), which refuses
 *   a value that is not a string, or cannot be an app id (500).
 * - [IDENTITY_METADATA] decides whether the backend receives what the gateway forwards about the caller (RC1-49):
 *   `true` does, `false` or any other value does not, and without it only a route to a registered service (`lb://`,
 *   `lb:ws://`) does: whoever runs one is inside the trust boundary, any other host might be a third party.
 */
@OptIn(InternalCoSecApi::class)
class PipelineGatewayFilter @InternalCoSecApi constructor(
    pipeline: Pipeline,
    contributors: List<BridgeContextContributor> = emptyList(),
    private val remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
    /** `cosec.authorization.scope.app-id`: the host app of a route without [APP_METADATA]. */
    private val host: HostScope = HttpFactsReader.NO_HOST,
    /**
     * `cosec.gateway.identity.forward-authorization`: whether the client's credentials go downstream, to a route that
     * receives the identity only. Off by default: a service behind needs none, and a compromised one then never sees
     * one. They are the headers, the query parameter and the cookie the pipeline can read them from
     * ([Pipeline.credentialHeaders], [Pipeline.withoutQueryToken], [Pipeline.withoutCookieToken]): `Authorization`, the
     * API key header, `authorization` in the query and the `authorization` cookie (RC1-5, R1-4 of #1194's review).
     */
    private val forwardAuthorization: Boolean = false,
    /** The body of a refusal (`PIP-34`): the host's renderer, or CoSec's own. */
    bodies: ProblemBodies = ProblemBodies.DEFAULT,
) : ReactivePipelineBridge(pipeline, contributors, remoteIpResolver, host, bodies),
    GlobalFilter,
    Ordered {
    private val credentials = CredentialStripping(pipeline)

    /**
     * What a route decides about its requests, by route id: computed once per [Route] instance, not per request (R2B-9).
     * A refreshed route is a new instance under its id and replaces the entry; one entry per route id.
     */
    private class RouteFacts(val route: Route) {
        val receivesIdentity = receivesIdentity(route)
        val attributes = mapOf(
            AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to route.id,
            IdentityIssuer.FORWARD_ATTRIBUTE to receivesIdentity.toString(),
        )

        /** The route's app as configured, of whatever type: the pipeline checks it, and makes it the host app (RC1-6). */
        val app: Any? = route.metadata[APP_METADATA]
    }

    private val routeFacts = ConcurrentHashMap<String, RouteFacts>()

    private fun factsOf(route: Route): RouteFacts {
        val known = routeFacts[route.id]
        if (known != null && known.route === route) return known
        return RouteFacts(route).also { routeFacts[route.id] = it }
    }

    override fun read(exchange: ServerWebExchange): HttpFacts {
        val route = exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
            ?: return HttpFactsReader.read(exchange, remoteIpResolver, host)
        val facts = factsOf(route)
        return HttpFactsReader.read(exchange, remoteIpResolver, host, facts.attributes, facts.app)
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        // Whatever identity the client claims is gone before anything reads the request. IdentityStripFilter, first of
        // all filters, has removed it where it runs: a request without it is not copied again (R2B-9).
        val stripped = if (exchange.request.headers.containsHeader(IDENTITY_HEADER)) {
            exchange.mutate().request { it.headers { headers -> headers.remove(IDENTITY_HEADER) } }.build()
        } else {
            exchange
        }
        return filterInternal(stripped) { authorized, outcome ->
            val request = authorized.request
            val route = authorized.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
            // The caller's credentials go only where its identity may go, only when asked for (RC1-5, RC1-49), and only
            // when the pipeline accepted them: a refused one, on a route anyone may use, stays here (R1A-7).
            val keepsCredentials = forwardAuthorization && outcome.security?.principal != null &&
                route != null && factsOf(route).receivesIdentity
            // Read before the builder's lambda: one it writes would be boxed, an allocation on every request.
            val cookies = if (keepsCredentials) null else credentials.cookies(request.headers)
            val downstream = request.mutate()
                .headers { headers ->
                    // Set, not added: the backend sees exactly the id the pipeline used, never the client's alongside it.
                    headers.set(CoSecParameters.REQUEST_ID, outcome.requestId)
                    // The method decided is the one the backend runs (`PIP-31`): no override header goes on.
                    removeMethodOverrides(headers)
                    outcome.forward.forEach { (name, value) ->
                        if (value == null) headers.remove(name) else headers.set(name, value)
                    }
                    if (!keepsCredentials) credentials.strip(headers, cookies)
                }
            if (!keepsCredentials) credentials.withoutQueryToken(request.uri)?.let(downstream::uri)
            // The last hop ([IdentityHeadersFilter]) forwards this value, and only this, whatever later filters do.
            outcome.forward[IdentityCodec.HEADER]?.let { authorized.attributes[IdentityHeadersFilter.FORWARDED_ATTRIBUTE] = it }
            chain.filter(authorized.mutate().request(credentials.handedOn(downstream.build(), cookies)).build())
        }
    }

    /**
     * Removes [METHOD_OVERRIDE_HEADERS] from [headers]: a loop over an array of lower-case names, so removing a header
     * that is absent allocates nothing ([MethodOverrideAllocationTest]).
     */
    private fun removeMethodOverrides(headers: HttpHeaders) {
        for (name in METHOD_OVERRIDE_HEADERS) {
            headers.remove(name)
        }
    }

    override fun getOrder(): Int = AUTHORIZATION_GATEWAY_FILTER_ORDER

    companion object {
        /**
         * The headers by which frameworks run a request as another method than its request line's (`X-HTTP-Method-Override`
         * of Google's APIs and Microsoft's, `X-HTTP-Method`, `X-Method-Override`), removed from every request the gateway
         * forwards, whatever the route (`PIP-31`). Header names are case-insensitive, so one removal covers every letter case;
         * written in lower case, which is the form the headers map looks keys up by: a lookup of another form first makes
         * the lower-case copy of the name, an allocation on every request.
         */
        private val METHOD_OVERRIDE_HEADERS = arrayOf("x-http-method-override", "x-http-method", "x-method-override")

        /** `CoSec-Identity` in lower case, the form the headers map looks names up by: no copy of it per request. */
        private val IDENTITY_HEADER = IdentityCodec.HEADER.lowercase(Locale.ROOT)

        /**
         * Filter order for gateway authorization.
         * Uses HIGHEST_PRECEDENCE + 10 to run very early in the gateway filter chain,
         * ensuring authorization is checked before route-specific filters.
         */
        @InternalCoSecApi
        const val AUTHORIZATION_GATEWAY_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10

        /**
         * Route metadata: `true` sends the route's backend the verified `CoSec-Identity` (and, with
         * `forward-authorization`, the caller's credentials), `false` never does; without it, only a `lb://` route
         * receives them (RC1-49).
         */
        const val IDENTITY_METADATA = "cosec-identity"

        /** Route metadata: the app of the route's requests, ahead of `cosec.authorization.scope.app-id` (RC1-6). */
        const val APP_METADATA = "cosec-app-id"

        /** The scheme of a route to a registered service, as Spring Cloud Gateway's load balancer reads it. */
        private const val LOAD_BALANCED = "lb"

        /**
         * Whether [route]'s backend receives what the gateway forwards about the caller: its metadata
         * [IDENTITY_METADATA] when it has one, exactly `true` (a Boolean or the string); without it, a route to a
         * registered service ([LOAD_BALANCED]).
         */
        internal fun receivesIdentity(route: Route): Boolean = when (val opted = route.metadata[IDENTITY_METADATA]) {
            null -> route.uri.scheme == LOAD_BALANCED
            else -> opted.toString() == "true"
        }
    }
}
