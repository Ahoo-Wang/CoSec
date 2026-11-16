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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.audit

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The change log is a record operators route, as the decision audit is: its logger name is a contract, not the name of
 * the class that happens to write it (RFC 0013 B4).
 */
class LoggingChangeListenerTest {
    private val event = ChangeEvent(
        "e",
        Instant.EPOCH,
        ChangeEvent.Kind.PUBLISHED,
        Actor(TenantId.PLATFORM, PrincipalId("plato")),
        ChangeId("pins-the-logger-name"),
        SnapshotKey.Platform,
        RevisionRef(1, "d"),
        null,
    )

    @Test
    fun `the change log is written to the logger me-ahoo-cosec-changelog`() {
        LoggingChangeListener.LOGGER_NAME.assert().isEqualTo("me.ahoo.cosec.changelog")
        val logger = LoggerFactory.getLogger("me.ahoo.cosec.changelog") as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        try {
            runBlocking { LoggingChangeListener().onChange(event) }
            // Other tests log through the same listener at the same time: this change's line is the one asked about.
            logged.list.filter { "pins-the-logger-name" in it.formattedMessage }
                .map { it.loggerName to it.formattedMessage }.assert().singleElement().satisfies({
                    it.first.assert().isEqualTo("me.ahoo.cosec.changelog")
                    it.second.assert().startsWith("Change pins-the-logger-name PUBLISHED on ")
                })
        } finally {
            logger.detachAppender(logged)
        }
    }
}
