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

package me.ahoo.cosec.runtime.authn

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cosec.spi.token.TokenNotYetValidException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration

/** `IDN-29`: a token not valid yet says "clock", at WARN at most once a minute, and at DEBUG every time. */
class EarlyTokensTest {
    @Test
    fun `warns at most once a minute`() {
        val logger = LoggerFactory.getLogger(TokenAuthenticator::class.java.name) as Logger
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        val level = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(lines)
        try {
            val early = TokenNotYetValidException("The token's iat is later than this instance's clock")
            val start = Long.MAX_VALUE / 2
            EarlyTokens.record(early, start)
            EarlyTokens.record(early, start + Duration.ofSeconds(30).toNanos())
            EarlyTokens.record(early, start + Duration.ofSeconds(61).toNanos())
            val warned = lines.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            warned.assert().hasSize(2)
            warned.first().assert().contains("not valid yet").contains("iat").contains(EarlyTokens.PAGE)
            lines.list.filter { it.level == Level.DEBUG }.assert().hasSize(1)
        } finally {
            logger.detachAppender(lines)
            logger.level = level
        }
    }
}
