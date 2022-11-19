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
import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

internal class ReactiveInjectSecurityContextWebFilterTest {

    @Test
    fun filter() {
        val filter = ReactiveInjectSecurityContextWebFilter(ReactiveInjectSecurityContextParser)
        val exchange = mockk<ServerWebExchange>() {
            every { request.headers.getFirst(Jwts.AUTHORIZATION_KEY) } returns null
            every { setSecurityContext(any()) } just runs
        }
        val chain = mockk<WebFilterChain>() {
            every { filter(exchange) } returns Mono.empty()
        }
        filter.filter(exchange, chain).block()
        verify {
            exchange.setSecurityContext(SecurityContext.ANONYMOUS)
        }
    }
}
