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

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.context.request.RequestTenantIdParser
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.webflux.ReactiveInjectSecurityContextParser
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.cosec.webflux.ReactiveRequestTenantIdParser
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class AuthorizationGatewayFilterTest {

    @Test
    fun filter() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val filter = AuthorizationGatewayFilter(
            ReactiveInjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRequestTenantIdParser.INSTANCE),
            authorization
        )
        assertThat(filter.order, equalTo(Ordered.HIGHEST_PRECEDENCE))
        val exchange = mockk<ServerWebExchange>() {
            every { request.headers.getFirst(Jwts.AUTHORIZATION_KEY) } returns null
            every { request.headers.getFirst(RequestTenantIdParser.TENANT_ID_KEY) } returns "tenantId"
            every { request.path.value() } returns "/path"
            every { request.methodValue } returns "GET"
            every { setSecurityContext(any()) } just runs
            every {
                mutate()
                    .principal(any())
                    .build()
            } returns this
        }
        val filterChain = mockk<GatewayFilterChain> {
            every { filter(exchange) } returns Mono.empty()
        }
        filter.filter(exchange, filterChain).block()
        verify {
            authorization.authorize(any(), any())
            exchange.setSecurityContext(any())
            filterChain.filter(exchange)
        }
    }
}
