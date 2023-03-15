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

package me.ahoo.cosec.opentelemetry.webflux

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class TraceWebFilterTest {

    @Test
    fun filter() {
        val exchange = mockk<ServerWebExchange> {
            every { getSecurityContext() } returns null
        }

        TraceWebFilter.filter(exchange) {
            Mono.empty()
        }.test()
            .verifyComplete()
    }

    @Test
    fun getOrder() {
        assertThat(TraceWebFilter.order, equalTo(Ordered.HIGHEST_PRECEDENCE + 1))
    }
}
