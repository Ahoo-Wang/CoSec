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

import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.writeSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * ReactiveInjectSecurityContextWebFilter .
 * 用于API网关授权检查后下游服务解析安全上下文，不需要进行Token校验。
 *
 * @author ahoo wang
 */
class ReactiveInjectSecurityContextWebFilter(
    private val securityContextParser: SecurityContextParser<ServerWebExchange>
) :
    WebFilter, Ordered {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        try {
            val securityContext = securityContextParser.parse(exchange)
            exchange.mutate()
                .principal(securityContext.principal.toMono())
                .build().let {
                    exchange.setSecurityContext(securityContext)
                    return chain.filter(it)
                        .writeSecurityContext(securityContext)
                }
        } catch (ignored: Throwable) {
            // ignored
        }
        return chain.filter(exchange)
    }

    override fun getOrder(): Int {
        return Ordered.HIGHEST_PRECEDENCE
    }
}
