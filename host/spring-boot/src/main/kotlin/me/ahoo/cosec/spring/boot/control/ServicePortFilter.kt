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
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * On the service port, nothing but the control API is served (`CTL-58`): a request there for any other path is a `404`
 * before the gate sees it, so a route of the application's own is never reachable by the services that were admitted for
 * the control plane. Requests on any other port pass untouched.
 */
internal class ServicePortFilter(
    private val listener: ControlServiceListener,
    private val basePath: String,
) : WebFilter, Ordered {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val local = exchange.request.localAddress?.port
        if (local == null || local != listener.boundPort) {
            return chain.filter(exchange)
        }
        val path = exchange.request.path.pathWithinApplication().value()
        if (path == basePath || path.startsWith("$basePath/")) {
            return chain.filter(exchange)
        }
        exchange.response.statusCode = HttpStatus.NOT_FOUND
        return exchange.response.setComplete()
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}
