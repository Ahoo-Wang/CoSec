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

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import me.ahoo.cosec.authorization.Authorization
import me.ahoo.cosec.authorization.AuthorizeResult
import me.ahoo.cosec.context.request.RequestTenantIdParser
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

internal class ReactiveAuthorizationFilterTest {

    @Test
    fun filter() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val filter = ReactiveAuthorizationFilter(
            ReactiveInjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRequestTenantIdParser.INSTANCE),
            authorization
        )
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
        val filterChain = mockk<WebFilterChain> {
            every { filter(exchange) } returns Mono.empty()
        }
        filter.filter(exchange, filterChain).block()
        verify {
            authorization.authorize(any(), any())
            exchange.setSecurityContext(any())
            filterChain.filter(exchange)
        }
    }

    @Test
    fun filterDeny() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.EXPLICIT_DENY.toMono()
        }
        val filter = ReactiveAuthorizationFilter(
            ReactiveInjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRequestTenantIdParser.INSTANCE),
            authorization
        )
        val exchange = mockk<ServerWebExchange>() {
            every { request.headers.getFirst(Jwts.AUTHORIZATION_KEY) } returns null
            every { request.headers.getFirst(RequestTenantIdParser.TENANT_ID_KEY) } returns "tenantId"
            every { request.path.value() } returns "/path"
            every { request.methodValue } returns "GET"
            every { response.setComplete() } returns Mono.empty()
            every { response.setStatusCode(HttpStatus.UNAUTHORIZED) } returns true
            every {
                mutate()
                    .principal(any())
                    .build()
            } returns this
        }

        filter.filter(exchange, mockk()).block()
        verify {
            authorization.authorize(any(), any())
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            exchange.response.setComplete()
        }
    }

    @Test
    fun filterDenyWhenAuthenticated() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.EXPLICIT_DENY.toMono()
        }
        val filter = ReactiveAuthorizationFilter(
            ReactiveInjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRequestTenantIdParser.INSTANCE),
            authorization
        )
        val tokenHeader =
            Jwts.TOKEN_PREFIX + "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJpZCIsInJvbGVzIjoiIiwibmFtZSI6Im5hbWUiLCJleHAiOjE2Njg4MjU3NzksImlhdCI6MTY2ODgyNTE3OSwianRpIjoidGVzdF8zUjg5bENYVkIifQ.EystLtgrZDr61K4pjOHsMn0M0FyXOfqdAxNmcK3Nqnw"
        val exchange = mockk<ServerWebExchange>() {
            every { request.headers.getFirst(Jwts.AUTHORIZATION_KEY) } returns tokenHeader
            every { request.headers.getFirst(RequestTenantIdParser.TENANT_ID_KEY) } returns "tenantId"
            every { request.path.value() } returns "/path"
            every { request.methodValue } returns "GET"
            every { response.setComplete() } returns Mono.empty()
            every { response.setStatusCode(HttpStatus.FORBIDDEN) } returns true
            every {
                mutate()
                    .principal(any())
                    .build()
            } returns this
        }

        filter.filter(exchange, mockk()).block()
        verify {
            authorization.authorize(any(), any())
            exchange.response.statusCode = HttpStatus.FORBIDDEN
            exchange.response.setComplete()
        }
    }
}