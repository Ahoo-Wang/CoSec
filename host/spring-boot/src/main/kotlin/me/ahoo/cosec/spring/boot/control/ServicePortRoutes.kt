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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * On the service port the control API's routes are served by this filter and by nothing else (`CTL-58`, `R3-5`): after the
 * gate has decided the request, the control routes are asked for it directly, and the request never reaches the
 * application's own handler mapping. A path under the control API's base that no control route answers is a `404` there,
 * whatever the application registered under it (a catch-all of the application's, ordered before the control routes,
 * would otherwise be reachable by the services that were admitted for the control plane). Requests on any other port
 * pass untouched.
 */
internal class ServicePortRoutes(
    private val listener: ControlServiceListener,
    private val routes: RouterFunction<ServerResponse>,
) : WebFilter, Ordered {
    private val strategies = HandlerStrategies.withDefaults()
    private val context = object : ServerResponse.Context {
        override fun messageWriters() = strategies.messageWriters()
        override fun viewResolvers() = strategies.viewResolvers()
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val local = exchange.request.localAddress?.port
        if (local == null || local != listener.boundPort) {
            return chain.filter(exchange)
        }
        val request = ServerRequest.create(exchange, strategies.messageReaders())
        return routes.route(request)
            .flatMap { handler -> handler.handle(request) }
            .flatMap { response -> response.writeTo(exchange, context) }
            .switchIfEmpty(Mono.defer { notFound(exchange) })
    }

    private fun notFound(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.NOT_FOUND
        return exchange.response.setComplete()
    }

    /** After the gate, which decides the request first; nothing after it. */
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
}
