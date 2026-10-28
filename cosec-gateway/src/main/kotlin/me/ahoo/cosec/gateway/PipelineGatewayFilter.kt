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

import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.identity.IdentityCodec
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
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

/**
 * The gateway filter over the coroutine pipeline (RFC 0006 §7.3): the same bridge as the WebFlux filter, so the two
 * answer identically (`WebAdapterParityTest`). The route id goes into the request attributes, and a permitted request
 * goes downstream with its `CoSec-Request-Id` and the headers the outcome forwards ([Outcome.forward]): the verified
 * `CoSec-Identity`, or none. A `CoSec-Identity` the client sent never reaches the pipeline nor a backend.
 */
class PipelineGatewayFilter(
    pipeline: Pipeline,
    contributors: List<BridgeContextContributor> = emptyList(),
    private val remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
    private val host: HostScope = HttpFactsReader.NO_HOST,
    /** `cosec.gateway.identity.forward-authorization`: whether the client's `Authorization` header goes downstream. */
    private val forwardAuthorization: Boolean = true,
) : ReactivePipelineBridge(pipeline, contributors, remoteIpResolver, host),
    GlobalFilter,
    Ordered {
    override fun read(exchange: ServerWebExchange): HttpFacts {
        val route = exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
            ?: return HttpFactsReader.read(exchange, remoteIpResolver, host)
        val attributes = mutableMapOf(AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to route.id)
        if (route.metadata[IDENTITY_METADATA]?.toString() == "false") {
            attributes[IdentityIssuer.FORWARD_ATTRIBUTE] = "false"
        }
        return HttpFactsReader.read(exchange, remoteIpResolver, host, attributes)
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
            val downstream = authorized.request.mutate()
                .headers { headers ->
                    // Set, not added: the backend sees exactly the id the pipeline used, never the client's alongside it.
                    headers.set(CoSecParameters.REQUEST_ID, outcome.requestId)
                    outcome.forward.forEach { (name, value) ->
                        if (value == null) headers.remove(name) else headers.set(name, value)
                    }
                    if (!forwardAuthorization) {
                        headers.remove(HttpHeaders.AUTHORIZATION)
                    }
                }
                .build()
            chain.filter(authorized.mutate().request(downstream).build())
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

        /** Route metadata: `false` keeps the route's backend from receiving `CoSec-Identity`, as for a third party. */
        const val IDENTITY_METADATA = "cosec-identity"
    }
}
