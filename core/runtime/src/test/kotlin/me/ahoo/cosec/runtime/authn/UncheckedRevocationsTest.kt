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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration

class UncheckedRevocationsTest {

    /** L6: the warning says how many tokens went through unchecked, which is what an operator acts on. */
    @Test
    fun theWarningCountsTheTokensAcceptedSoFar() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(UncheckedRevocations::class.java) as Logger
        logger.addAppender(appender)
        try {
            val unchecked = UncheckedRevocations(Duration.ZERO)
            unchecked.record(RevocationUnavailableException("down"))
            unchecked.record(RevocationUnavailableException("down"))
            appender.list.map { it.formattedMessage }.assert().containsExactly(
                "Token revocations cannot be read: tokens are accepted without a revocation check (1 so far).",
                "Token revocations cannot be read: tokens are accepted without a revocation check (2 so far).",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }
}
