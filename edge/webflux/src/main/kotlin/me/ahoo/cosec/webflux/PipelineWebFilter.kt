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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import me.ahoo.cosec.runtime.http.RequestGate
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * [exchange] with [Outcome.forward] applied to the request the chain sees: the headers the gate sets on it or removes,
 * such as a `CoSec-Identity` a client sent to an enforcing host (RC1-18). [exchange] itself when that changes nothing,
 * so a request without such a header is not copied.
 */
internal fun forwarded(exchange: ServerWebExchange, outcome: Outcome): ServerWebExchange {
    val headers = exchange.request.headers
    if (outcome.forward.none { (name, value) -> value != null || headers.containsHeader(name) }) {
        return exchange
    }
    return exchange.mutate().request {
        it.headers { mutable ->
            outcome.forward.forEach { (name, value) ->
                if (value == null) {
                    mutable.remove(
                        name
                    )
                } else {
                    mutable.set(name, value)
                }
            }
        }
    }.build()
}

/** The WebFlux filter over the coroutine pipeline (RFC 0006 §7.3). */
class PipelineWebFilter @InternalCoSecApi constructor(
    pipeline: RequestGate,
    contributors: List<BridgeContextContributor> = emptyList(),
    remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
    host: HostScope = HttpFactsReader.NO_HOST,
) : ReactivePipelineBridge(pipeline, contributors, remoteIpResolver, host),
    WebFilter,
    Ordered {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        filterInternal(exchange) { authorized, outcome -> chain.filter(forwarded(authorized, outcome)) }

    override fun getOrder(): Int = REACTIVE_AUTHORIZATION_FILTER_ORDER

    companion object {
        /**
         * Filter order for reactive authorization.
         * Set to 1000 to run after framework filters (CORS, etc.)
         * while still running before most application filters.
         */
        const val REACTIVE_AUTHORIZATION_FILTER_ORDER = 1000
    }
}
