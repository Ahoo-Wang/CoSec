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

import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.writeSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Reactive WebFilter for injecting security context without token verification.
 *
 * This filter is designed for downstream services behind an API gateway that has
 * already performed authorization checks. It parses the security context from
 * request headers without requiring token verification.
 *
 * @param requestParser Parser for converting exchanges to requests
 * @param securityContextParser Parser for extracting security context
 * @see SecurityContextParser.ensureParse
 */
class ReactiveInjectSecurityContextWebFilter(
    private val requestParser: RequestParser<ServerWebExchange>,
    private val securityContextParser: SecurityContextParser
) : WebFilter, Ordered {
    companion object {
        const val REACTIVE_INJECT_SECURITY_CONTEXT_WEB_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = requestParser.parse(exchange)
        val securityContext = securityContextParser.ensureParse(request)
        securityContext.setRequest(request)
        exchange.mutate()
            .principal(securityContext.principal.toMono())
            .build().let {
                exchange.setSecurityContext(securityContext)
                return chain.filter(it)
                    .writeSecurityContext(securityContext)
            }
    }

    override fun getOrder(): Int {
        return REACTIVE_INJECT_SECURITY_CONTEXT_WEB_FILTER_ORDER
    }
}
