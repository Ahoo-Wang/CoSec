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

package me.ahoo.cosec.control.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.captured
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.document.json.StoredRecord
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration

/** SNP-27: the control plane tells a record a later version wrote at WARN once per interval, and nothing else as one. */
class LaterRecordsTest {
    private val later = IllegalStateException(
        "decoding",
        SnapshotFormatException("later", StoredFormat.Later(StoredRecord.CHANGE, listOf("device"))),
    )

    @Test
    fun `a later record is told at WARN once per interval, and a broken store is not told as one`() {
        val logger = LoggerFactory.getLogger("me.ahoo.cosec.control") as Logger
        val level = logger.level
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        logger.level = Level.DEBUG
        // Well after any warning another test made with the real clock.
        val start = System.nanoTime() + Duration.ofHours(1).toNanos()
        try {
            LaterRecords.told("call", SnapshotFormatException("A change request is malformed.")).assert().isFalse()
            LaterRecords.told("call", IllegalStateException("redis down")).assert().isFalse()
            LaterRecords.told("call", later) { start }.assert().isTrue()
            LaterRecords.told("call", later) { start + 1 }.assert().isTrue()
            LaterRecords.told("call", later) { start + Duration.ofMinutes(1).toNanos() }.assert().isTrue()
        } finally {
            logger.level = level
            logger.detachAppender(logged)
        }
        logged.captured().map { it.level }.assert().containsExactly(Level.WARN, Level.DEBUG, Level.WARN)
        logged.captured().first().formattedMessage.assert().isEqualTo(
            "call is 503: a later CoSec wrote a stored record (the change record), members [device] in its crit, " +
                "which this instance does not read and never rewrites; an upgraded instance reads it. Upgrade this " +
                "instance; nothing is wrong with the store."
        )
    }
}
