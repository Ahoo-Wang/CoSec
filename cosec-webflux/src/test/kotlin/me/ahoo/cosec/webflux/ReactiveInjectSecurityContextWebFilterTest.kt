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
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.context.AUTHORIZATION_HEADER_KEY
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import me.ahoo.test.asserts.assert
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

internal class ReactiveInjectSecurityContextWebFilterTest {

    @Test
    fun filter() {
        val filter = ReactiveInjectSecurityContextWebFilter(
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            InjectSecurityContextParser
        )
        filter.order.assert().isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10)

        assertThat(filter.order, equalTo(Ordered.HIGHEST_PRECEDENCE + 10))
        val exchange = mockk<ServerWebExchange> {
            every { request.headers.getFirst(RequestIdCapable.REQUEST_ID_KEY) } returns null
            every { request.headers.getFirst(AUTHORIZATION_HEADER_KEY) } returns null
            every { request.path.value() } returns "/path"
            every { request.method.name() } returns "GET"
            every { request.remoteAddress?.hostName } returns "hostName"
            every { request.headers.origin } returns null
            every { request.headers.getFirst(HttpHeaders.REFERER) } returns null
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
            exchange.setSecurityContext(any())
        }
    }
}
