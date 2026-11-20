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

package me.ahoo.cosec.webmvc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.webmvc.ServletRequests.requireSecurityContext
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest

/**
 * UA-7: on Spring MVC, a reader's name says whether it throws or answers null, and a throwing reader says what to do
 * and names the stack's other reader.
 */
class UnboundReadersTest {
    @Test
    fun `the request's reader throws IllegalStateException naming the handler parameter and the holder`() {
        val request = MockHttpServletRequest()
        request.securityContextOrNull().assert().isNull()
        val message = assertThrows<IllegalStateException> { request.requireSecurityContext() }.message
        message.assert().isEqualTo(Unbound.Receiver.REQUEST.missing)
            .contains("Register CoSec's filter").contains("handler parameter")
            .contains("CoSecSecurityContextHolder.requireSecurityContext()")
    }

    @Test
    fun `the holder throws IllegalStateException naming the request's reader`() {
        CoSecSecurityContextHolder.securityContextOrNull().assert().isNull()
        val message = assertThrows<IllegalStateException> { CoSecSecurityContextHolder.requireSecurityContext() }.message
        message.assert().isEqualTo(Unbound.Receiver.THREAD.missing)
            .contains("handler parameter").contains("ServletRequests.requireSecurityContext(request)")
    }

    /** PIP-27 with the drill's finding: a context the pipeline did not make is ignored, and says so. */
    @Test
    @OptIn(InternalCoSecApi::class)
    fun `a context the pipeline did not make is ignored, the throwing reader says so, and null warns once`() {
        val foreign = mockk<SecurityContext>()
        val request = MockHttpServletRequest().apply { setAttribute(SecurityContext.KEY, foreign) }
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(CoSecSecurityContextHolder::class.java) as Logger
        logger.addAppender(lines)
        CoSecSecurityContextHolder.setContext(foreign)
        try {
            Unbound.resetWarnings()
            repeat(2) {
                request.securityContextOrNull().assert().isNull()
                CoSecSecurityContextHolder.securityContextOrNull().assert().isNull()
            }
            lines.list.map { it.formattedMessage }.assert().containsExactly(
                Unbound.foreign(Unbound.Receiver.REQUEST),
                Unbound.foreign(Unbound.Receiver.THREAD),
            )
            assertThrows<IllegalStateException> { request.requireSecurityContext() }.message.assert()
                .isEqualTo(Unbound.foreign(Unbound.Receiver.REQUEST)).contains("did not make").contains("PIP-27")
            assertThrows<IllegalStateException> { CoSecSecurityContextHolder.requireSecurityContext() }.message.assert()
                .isEqualTo(Unbound.foreign(Unbound.Receiver.THREAD))
        } finally {
            logger.detachAppender(lines)
            CoSecSecurityContextHolder.remove()
        }
    }
}
