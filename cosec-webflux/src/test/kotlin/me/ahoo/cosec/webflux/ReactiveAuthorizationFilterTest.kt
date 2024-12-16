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

import com.auth0.jwt.algorithms.Algorithm
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.context.AUTHORIZATION_HEADER_KEY
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.token.TokenVerificationException
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import me.ahoo.cosid.test.MockIdGenerator
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

internal class ReactiveAuthorizationFilterTest {
    companion object {
        val algorithm = Algorithm.HMAC256("FyN0Igd80Gas8stTavArGKOYnS9uLWGA_")
        val jwtTokenConverter = JwtTokenConverter(MockIdGenerator.INSTANCE, algorithm)
        fun createAccessToken(principal: SimplePrincipal): String {
            return jwtTokenConverter.toToken(principal).accessToken
        }
    }

    @Test
    fun filter() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val filter = ReactiveAuthorizationFilter(
            InjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            authorization,
        )
        assertThat(filter.order, equalTo(Ordered.HIGHEST_PRECEDENCE + 10))
        val exchange = mockk<ServerWebExchange> {
            every { request.headers.getFirst(AUTHORIZATION_HEADER_KEY) } returns null
            every { request.headers.origin } returns "origin"
            every { request.headers.getFirst(HttpHeaders.REFERER) } returns "REFERER"
            every { request.path.value() } returns "/path"
            every { request.method.name() } returns "GET"
            every { request.remoteAddress?.hostName } returns "hostName"
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
    fun filterWhenTokenInvalid() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.EXPLICIT_DENY.toMono()
        }
        val securityContextParser = mockk<SecurityContextParser> {
            every { parse(any()) } throws TokenVerificationException()
        }
        val filter = ReactiveAuthorizationFilter(
            securityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            authorization,
        )
        val exchange = mockk<ServerWebExchange> {
            every { request.headers.origin } returns "origin"
            every { request.headers.getFirst(HttpHeaders.REFERER) } returns "REFERER"
            every { request.path.value() } returns "/path"
            every { request.method.name() } returns "GET"
            every { request.remoteAddress?.hostName } returns "hostName"
            every { response.setStatusCode(HttpStatus.UNAUTHORIZED) } returns true
            every { response.headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { response.bufferFactory().wrap(any() as ByteArray) } returns mockk()
            every { response.writeWith(any()) } returns Mono.empty()
            every { setSecurityContext(any()) } just runs
            every {
                mutate()
                    .principal(any())
                    .build()
            } returns this
        }
        val filterChain = mockk<WebFilterChain>()
        filter.filter(exchange, filterChain).block()

        verify {
            securityContextParser.parse(any())
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            exchange.response.bufferFactory().wrap(any() as ByteArray)
            exchange.response.writeWith(any())
            authorization.authorize(any(), any())
            exchange.setSecurityContext(any())
        }
    }

    @Test
    fun filterDeny() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.EXPLICIT_DENY.toMono()
        }
        val filter = ReactiveAuthorizationFilter(
            InjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            authorization,
        )
        val exchange = mockk<ServerWebExchange> {
            every { request.headers.getFirst(AUTHORIZATION_HEADER_KEY) } returns null
            every { request.headers.origin } returns "origin"
            every { request.headers.getFirst(HttpHeaders.REFERER) } returns "REFERER"
            every { request.path.value() } returns "/path"
            every { request.method.name() } returns "GET"
            every { request.remoteAddress?.hostName } returns "hostName"
            every { response.setStatusCode(HttpStatus.UNAUTHORIZED) } returns true
            every { response.headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { response.bufferFactory().wrap(any() as ByteArray) } returns mockk()
            every { response.writeWith(any()) } returns Mono.empty()
            every { setSecurityContext(any()) } just runs
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
            exchange.response.bufferFactory().wrap(any() as ByteArray)
            exchange.response.writeWith(any())
            exchange.setSecurityContext(any())
        }
    }

    @Test
    fun filterDenyWhenAuthenticated() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.EXPLICIT_DENY.toMono()
        }
        val filter = ReactiveAuthorizationFilter(
            InjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            authorization,
        )
        val principal = SimplePrincipal("id")
        val accessToken = createAccessToken(principal)
        val tokenHeader =
            Jwts.TOKEN_PREFIX + accessToken
        val exchange = mockk<ServerWebExchange> {
            every { request.headers.getFirst(AUTHORIZATION_HEADER_KEY) } returns tokenHeader
            every { request.headers.origin } returns "origin"
            every { request.headers.getFirst(HttpHeaders.REFERER) } returns "REFERER"
            every { request.path.value() } returns "/path"
            every { request.method.name() } returns "GET"
            every { request.remoteAddress?.hostName } returns "hostName"
            every { response.setStatusCode(HttpStatus.FORBIDDEN) } returns true
            every { response.headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { response.bufferFactory().wrap(any() as ByteArray) } returns mockk()
            every { response.writeWith(any()) } returns Mono.empty()
            every { setSecurityContext(any()) } just runs
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
            exchange.response.bufferFactory().wrap(any() as ByteArray)
            exchange.response.writeWith(any())
            exchange.setSecurityContext(any())
        }
    }

    @Test
    fun filterWhenTooManyRequests() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns TooManyRequestsException().toMono()
        }
        val filter = ReactiveAuthorizationFilter(
            InjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            authorization,
        )
        val exchange = mockk<ServerWebExchange> {
            every { request.headers.getFirst(AUTHORIZATION_HEADER_KEY) } returns null
            every { request.headers.origin } returns "origin"
            every { request.headers.getFirst(HttpHeaders.REFERER) } returns "REFERER"
            every { request.path.value() } returns "/path"
            every { request.method.name() } returns "GET"
            every { request.remoteAddress?.hostName } returns "hostName"
            every { response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS) } returns true
            every { response.headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { response.bufferFactory().wrap(any() as ByteArray) } returns mockk()
            every { response.writeWith(any()) } returns Mono.empty()
            every { setSecurityContext(any()) } just runs
            every {
                mutate()
                    .principal(any())
                    .build()
            } returns this
        }

        filter.filter(exchange, mockk()).block()
        verify {
            authorization.authorize(any(), any())
            exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
            exchange.response.bufferFactory().wrap(any() as ByteArray)
            exchange.response.writeWith(any())
            exchange.setSecurityContext(any())
        }
    }
}
