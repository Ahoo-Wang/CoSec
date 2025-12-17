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

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.context.request.RequestIdCapable.Companion.REQUEST_ID_KEY
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.webflux.ReactiveSecurityFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Authorization Gateway Filter .
 *
 * @author ahoo wang
 */
class AuthorizationGatewayFilter(
    securityContextParser: SecurityContextParser,
    requestParser: RequestParser<ServerWebExchange>,
    authorization: Authorization
) : GlobalFilter, Ordered, ReactiveSecurityFilter(securityContextParser, requestParser, authorization) {
    companion object {
        const val AUTHORIZATION_GATEWAY_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        return filterInternal(exchange) { serverExchange, request ->
            val serverHttpRequest = serverExchange.request.mutate()
                .header(REQUEST_ID_KEY, request.requestId)
                .build()
            val nextServerExchange = serverExchange.mutate().request(serverHttpRequest).build()
            chain.filter(nextServerExchange)
        }
    }

    override fun getOrder(): Int {
        return AUTHORIZATION_GATEWAY_FILTER_ORDER
    }
}
