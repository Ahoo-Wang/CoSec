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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.requireSecurityContext
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.securityContextOrNull
import me.ahoo.cosec.webflux.ServerWebExchanges.requireSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.securityContextOrNull
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
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
        message.assert().isEqualTo(Unbound.missing(Unbound.Receiver.REACTOR_CONTEXT))
            .contains("Register CoSec's WebFilter").contains("ServerWebExchanges.securityContextOrNull(exchange)")
    }

    @Test
    fun `the exchange's reader throws IllegalStateException naming the Reactor context's`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
        exchange.securityContextOrNull().assert().isNull()
        val message = assertThrows<IllegalStateException> { exchange.requireSecurityContext() }.message
        message.assert().isEqualTo(Unbound.missing(Unbound.Receiver.EXCHANGE)).contains("Register CoSec's WebFilter")
        message.assert().contains("requireSecurityContext()").contains("ReactiveSecurityContexts")
    }

    /** PIP-27 with the drill's finding: a context the pipeline did not make is ignored, and says so. */
    @Test
    fun `a context the pipeline did not make is ignored, the throwing reader says so, and null warns once`() {
        val foreign = mockk<SecurityContext>()
        val reactor = Context.of(SecurityContext.KEY, foreign)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
        exchange.attributes[SecurityContext.KEY] = foreign
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(ReactiveSecurityContexts::class.java) as Logger
        logger.addAppender(lines)
        try {
            Unbound.resetWarnings()
            repeat(2) {
                reactor.securityContextOrNull().assert().isNull()
                exchange.securityContextOrNull().assert().isNull()
            }
            lines.list.map { it.formattedMessage }.assert().containsExactly(
                Unbound.foreign(Unbound.Receiver.REACTOR_CONTEXT),
                Unbound.foreign(Unbound.Receiver.EXCHANGE),
            )
        } finally {
            logger.detachAppender(lines)
        }
        assertThrows<IllegalStateException> { reactor.requireSecurityContext() }.message.assert()
            .isEqualTo(Unbound.foreign(Unbound.Receiver.REACTOR_CONTEXT)).contains("did not make").contains("PIP-27")
        assertThrows<IllegalStateException> { exchange.requireSecurityContext() }.message.assert()
            .isEqualTo(Unbound.foreign(Unbound.Receiver.EXCHANGE))
    }
}
