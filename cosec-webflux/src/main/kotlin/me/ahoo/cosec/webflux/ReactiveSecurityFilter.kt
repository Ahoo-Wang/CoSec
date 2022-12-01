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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.authorization.Authorization
import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.policy.serialization.CoSecJsonSerializer
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.util.context.Context

abstract class ReactiveSecurityFilter(
    val securityContextParser: SecurityContextParser<ServerWebExchange>,
    val requestParser: RequestParser<ServerWebExchange>,
    val authorization: Authorization
) {
    fun filterInternal(exchange: ServerWebExchange, chain: (ServerWebExchange) -> Mono<Void>): Mono<Void> {
        val securityContext = securityContextParser.parse(exchange)
        val request = requestParser.parse(exchange)
        return authorization.authorize(request, securityContext)
            .flatMap { authorizeResult ->
                if (authorizeResult.authorized) {
                    exchange.mutate()
                        .principal(securityContext.principal.toMono())
                        .build().let {
                            exchange.setSecurityContext(securityContext)
                            return@flatMap chain(it)
                                .contextWrite { ctx: Context -> ctx.put(SecurityContext.KEY, securityContext) }
                        }
                }
                val principal = securityContext.principal
                if (!principal.authenticated()) {
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                } else {
                    exchange.response.statusCode = HttpStatus.FORBIDDEN
                }
                val builder =
                    exchange.response.bufferFactory().wrap(CoSecJsonSerializer.writeValueAsBytes(authorizeResult))
                        .toMono()
                exchange.response.writeWith(builder)
            }
    }
}
