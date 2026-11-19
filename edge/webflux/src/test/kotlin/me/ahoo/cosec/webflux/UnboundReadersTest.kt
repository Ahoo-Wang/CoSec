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

import me.ahoo.cosec.webflux.ReactiveSecurityContexts.requireSecurityContext
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.securityContextOrNull
import me.ahoo.cosec.webflux.ServerWebExchanges.requireSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.securityContextOrNull
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.util.context.Context

/**
 * UA-7: on WebFlux, a reader's name says whether it throws or answers null, and a throwing reader says what to do and
 * names the stack's other reader.
 */
class UnboundReadersTest {
    @Test
    fun `the Reactor context's reader throws IllegalStateException naming the exchange's`() {
        Context.empty().securityContextOrNull().assert().isNull()
        val message = assertThrows<IllegalStateException> { Context.empty().requireSecurityContext() }.message
        message.assert().isEqualTo(Unbound.IN_REACTOR_CONTEXT)
            .contains("Register CoSec's WebFilter").contains("ServerWebExchanges.securityContextOrNull(exchange)")
    }

    @Test
    fun `the exchange's reader throws IllegalStateException naming the Reactor context's`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
        exchange.securityContextOrNull().assert().isNull()
        val message = assertThrows<IllegalStateException> { exchange.requireSecurityContext() }.message
        message.assert().isEqualTo(Unbound.ON_EXCHANGE).contains("Register CoSec's WebFilter")
        message.assert().contains("requireSecurityContext()").contains("ReactiveSecurityContexts")
    }
}
