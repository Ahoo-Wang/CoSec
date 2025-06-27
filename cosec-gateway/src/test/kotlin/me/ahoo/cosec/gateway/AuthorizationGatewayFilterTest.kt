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
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.test.asserts.assert
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class AuthorizationGatewayFilterTest {

    @Test
    fun filter() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val filter = AuthorizationGatewayFilter(
            InjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            authorization,
        )
        assertThat(filter.order, equalTo(Ordered.HIGHEST_PRECEDENCE + 10))
        val serverRequest = MockServerHttpRequest.get("/path")
            .header(HttpHeaders.ORIGIN, "origin")
            .build()
        val serverExchange = MockServerWebExchange.builder(serverRequest).build()
        val filterChain = GatewayFilterChain {
            it.getSecurityContext().assert().isNotNull()
            it.request.headers.contains(RequestIdCapable.REQUEST_ID_KEY).assert().isTrue()
            Mono.empty()
        }
        filter.filter(serverExchange, filterChain).block()
        verify {
            authorization.authorize(any(), any())
        }
    }
}
