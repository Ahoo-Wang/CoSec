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
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Reactive Authorization Web Filter .
 *
 * @author ahoo wang
 */
class ReactiveAuthorizationFilter(
    securityContextParser: SecurityContextParser<ServerWebExchange>,
    requestParser: RequestParser<ServerWebExchange>,
    authorization: Authorization
) : WebFilter, ReactiveSecurityFilter(securityContextParser, requestParser, authorization) {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        return filterInternal(exchange) {
            chain.filter(it)
        }
    }
}