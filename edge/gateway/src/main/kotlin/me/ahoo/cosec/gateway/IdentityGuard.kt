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

import me.ahoo.cosec.runtime.identity.IdentityCodec
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Strips `CoSec-Identity` from every request a gateway receives, first of all filters and whatever else is configured:
 * a reserved header a client sends is a forged identity. On whenever `cosec-gateway` is present, whatever the mode or
 * `cosec.enabled`: without the pipeline filter, a backend still never sees a client's identity.
 */
class IdentityStripFilter : GlobalFilter, Ordered {
    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        if (!exchange.request.headers.containsHeader(IdentityCodec.HEADER)) {
            return chain.filter(exchange)
        }
        val stripped = exchange.mutate().request {
            it.headers { headers ->
                headers.remove(
                    IdentityCodec.HEADER
                )
            }
        }.build()
        return chain.filter(stripped)
    }

    override fun getOrder(): Int = ORDER

    companion object {
        /** Before the pipeline filter ([PipelineGatewayFilter.AUTHORIZATION_GATEWAY_FILTER_ORDER]) and anything else. */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE
    }
}

/**
 * The last hop: the routing filters (Netty and WebSocket) apply it to the request they send, after every route and
 * global filter, so a filter that added or copied `CoSec-Identity` (`AddRequestHeader`, `SetRequestHeader`,
 * `MapRequestHeader`, a custom one) cannot forward an identity: the backend receives exactly what the pipeline
 * forwarded ([FORWARDED_ATTRIBUTE]), or none.
 */
class IdentityHeadersFilter : HttpHeadersFilter {
    override fun filter(input: HttpHeaders, exchange: ServerWebExchange): HttpHeaders {
        val forwarded = exchange.getAttribute<String>(FORWARDED_ATTRIBUTE)
        if (!input.containsHeader(IdentityCodec.HEADER) && forwarded == null) {
            return input
        }
        val headers = HttpHeaders()
        headers.putAll(input)
        headers.remove(IdentityCodec.HEADER)
        forwarded?.let { headers.set(IdentityCodec.HEADER, it) }
        return headers
    }

    override fun supports(type: HttpHeadersFilter.Type): Boolean = type == HttpHeadersFilter.Type.REQUEST

    companion object {
        /** The exchange attribute [PipelineGatewayFilter] sets to the identity it forwards; absent when it forwards none. */
        const val FORWARDED_ATTRIBUTE = "cosec.identity.forwarded"
    }
}
