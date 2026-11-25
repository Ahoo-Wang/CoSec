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

package me.ahoo.cosec.login.token

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.document.json.StoredRecord
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind.PERSON
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * SNP-27 on the refresh: a session record a later version wrote, which this version refuses, is answered as an outage
 * (503, nothing issued, the session kept) and never rewritten; the log tells it apart from a store that is broken.
 */
class LaterSessionRecordTest {
    private val later = SnapshotFormatException(
        "A stored token family names members in crit that this version does not understand.",
        StoredFormat.Later(StoredRecord.SESSION, listOf("device")),
    )

    /** A store whose one record a later version wrote: every read refuses it, and a write fails the test. */
    private val store = object : TokenFamilyStore {
        override suspend fun create(family: TokenFamily): Boolean = throw AssertionError("nothing is created")

        override suspend fun read(id: String): TokenFamilyStore.Read? = throw later

        override suspend fun replace(version: String, next: TokenFamily): Boolean =
            throw AssertionError("a record this version refused was rewritten")
    }

    private val families = TokenFamilies(store)

    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.formattedMessage.contains("a later CoSec wrote the session record") }
    }

    @Test
    fun `a refresh of a session a later version wrote is an outage, and the record is never rewritten`() = runTest {
        val session = TokenSession("f1", Instant.now(), 1)
        val token = VerifiedToken("jti", "alice", "acme", null, PERSON, session = session)
        val refusal = assertThrows<SessionStoreUnavailableException> { families.rotate(token) }
        StoredFormat.later(refusal)?.members.assert().containsExactly("device")
        assertThrows<SessionStoreUnavailableException> {
            families.requireLive(session, TenantId("acme"), PrincipalId("alice"))
        }
    }

    @Test
    fun `the log says a later version wrote it, once per interval at WARN, and a broken store is not told so`() {
        val events = capture {
            LaterSessionRecords.told("read a session", later) { System.nanoTime() + WELL_AFTER }.assert().isTrue()
            LaterSessionRecords.told("read a session", later) { System.nanoTime() + WELL_AFTER }.assert().isTrue()
            LaterSessionRecords.told("read a session", IllegalStateException("redis down")).assert().isFalse()
        }
        events.filter { it.level == Level.WARN }.size.assert().isEqualTo(1)
        events.first { it.level == Level.WARN }.formattedMessage.assert()
            .contains("(members [device] in its crit)").contains("answers 503; the session is kept")
    }

    private companion object {
        /** Later than any warning of an earlier test, so this one's first is at WARN. */
        const val WELL_AFTER = 3_600_000_000_000L
    }
}
