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
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.webflux.HttpFactsReader
import me.ahoo.cosec.webflux.ReactivePipelineBridge
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * The gateway filter over the coroutine pipeline (RFC 0006 §7.3): the same bridge as the WebFlux filter, so the two
 * answer identically (`WebAdapterParityTest`). The route id goes into the request attributes, and a permitted request
 * goes downstream with its `CoSec-Request-Id`.
 */
class PipelineGatewayFilter(
    pipeline: Pipeline,
    contributors: List<BridgeContextContributor> = emptyList(),
    private val remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
    private val host: HostScope = HttpFactsReader.NO_HOST,
) : ReactivePipelineBridge(pipeline, contributors, remoteIpResolver, host),
    GlobalFilter,
    Ordered {
    override fun read(exchange: ServerWebExchange): HttpFacts {
        val route = exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
            ?: return HttpFactsReader.read(exchange, remoteIpResolver, host)
        return HttpFactsReader.read(
            exchange,
            remoteIpResolver,
            host,
            mapOf(AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to route.id)
        )
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        filterInternal(exchange) { authorized, outcome ->
            val downstream = authorized.request.mutate().header(CoSecParameters.REQUEST_ID, outcome.requestId).build()
            chain.filter(authorized.mutate().request(downstream).build())
        }

    override fun getOrder(): Int = AuthorizationGatewayFilter.AUTHORIZATION_GATEWAY_FILTER_ORDER
}
