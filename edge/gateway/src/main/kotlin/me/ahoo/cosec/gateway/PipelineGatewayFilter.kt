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

package me.ahoo.cosec.gateway

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import me.ahoo.cosec.runtime.identity.IdentityCodec
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
import java.net.URI

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
class PipelineGatewayFilter(
    pipeline: Pipeline,
    contributors: List<BridgeContextContributor> = emptyList(),
    private val remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
    /** `cosec.authorization.scope.app-id`: the host app of a route without [APP_METADATA]. */
    private val host: HostScope = HttpFactsReader.NO_HOST,
    /**
     * `cosec.gateway.identity.forward-authorization`: whether the client's credentials go downstream, to a route that
     * receives the identity only. Off by default: a service behind needs none, and a compromised one then never sees
     * one. They are the headers, the query parameter and the cookie the pipeline reads them from
     * ([Pipeline.credentialHeaders], [Pipeline.withoutQueryToken], [Pipeline.withoutCookieToken]): `Authorization`, the
     * API key header, `authorization` in the query and the `authorization` cookie (RC1-5, R1-4 of #1194's review).
     */
    private val forwardAuthorization: Boolean = false,
) : ReactivePipelineBridge(pipeline, contributors, remoteIpResolver, host),
    GlobalFilter,
    Ordered {
    private val credentialHeaders: Set<String> = pipeline.credentialHeaders
    private val withoutQueryToken: (String?) -> String? = pipeline::withoutQueryToken
    private val withoutCookieToken: (String) -> String? = pipeline::withoutCookieToken

    override fun read(exchange: ServerWebExchange): HttpFacts {
        val route = exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
            ?: return HttpFactsReader.read(exchange, remoteIpResolver, host)
        val attributes = mapOf(
            AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to route.id,
            IdentityIssuer.FORWARD_ATTRIBUTE to receivesIdentity(route).toString(),
        )
        // The route's app as configured, of whatever type: the pipeline checks it, and makes it the host app (RC1-6).
        val app = route.metadata[APP_METADATA]
        return HttpFactsReader.read(exchange, remoteIpResolver, host, attributes, app)
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        // Whatever identity the client claims is gone before anything reads the request.
        val stripped = exchange.mutate().request {
            it.headers { headers ->
                headers.remove(
                    IdentityCodec.HEADER
                )
            }
        }.build()
        return filterInternal(stripped) { authorized, outcome ->
            val request = authorized.request
            val route = authorized.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
            // The caller's credentials go only where its identity may go, and only when asked for (RC1-5, RC1-49).
            val credentials = forwardAuthorization && route != null && receivesIdentity(route)
            val downstream = request.mutate()
                .headers { headers ->
                    // Set, not added: the backend sees exactly the id the pipeline used, never the client's alongside it.
                    headers.set(CoSecParameters.REQUEST_ID, outcome.requestId)
                    outcome.forward.forEach { (name, value) ->
                        if (value == null) headers.remove(name) else headers.set(name, value)
                    }
                    if (!credentials) {
                        credentialHeaders.forEach(headers::remove)
                        withoutCookieTokens(headers)
                    }
                }
            if (!credentials) {
                val query = request.uri.rawQuery
                val kept = withoutQueryToken(query)
                if (kept != query) {
                    downstream.uri(withQuery(request.uri, kept))
                }
            }
            // The last hop ([IdentityHeadersFilter]) forwards this value, and only this, whatever later filters do.
            outcome.forward[IdentityCodec.HEADER]?.let { authorized.attributes[IdentityHeadersFilter.FORWARDED_ATTRIBUTE] = it }
            chain.filter(authorized.mutate().request(downstream.build()).build())
        }
    }

    /** Each `Cookie` header without the `authorization` cookie; one left with no cookie goes, and so does the name. */
    private fun withoutCookieTokens(headers: HttpHeaders) {
        val cookies = headers[HttpHeaders.COOKIE] ?: return
        val kept = cookies.mapNotNull(withoutCookieToken)
        when {
            kept == cookies -> Unit
            kept.isEmpty() -> headers.remove(HttpHeaders.COOKIE)
            else -> headers.put(HttpHeaders.COOKIE, kept)
        }
    }

    override fun getOrder(): Int = AUTHORIZATION_GATEWAY_FILTER_ORDER

    companion object {
        /**
         * Filter order for gateway authorization.
         * Uses HIGHEST_PRECEDENCE + 10 to run very early in the gateway filter chain,
         * ensuring authorization is checked before route-specific filters.
         */
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
         * [uri] with its raw query replaced by [query], none when null: the rest of the text as received, so nothing is
         * encoded or checked again (a query of the original URI less some pairs is as valid as the original).
         */
        private fun withQuery(uri: URI, query: String?): URI {
            val text = uri.toString()
            val end = uri.rawFragment?.let { text.length - it.length - 1 } ?: text.length
            return URI.create(
                text.substring(0, text.indexOf('?')) + query?.let { "?$it" }.orEmpty() + text.substring(end)
            )
        }

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
