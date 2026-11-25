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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.captured
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.document.json.StoredRecord
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * SNP-27: the control API answers a record a later version wrote with a 503, and tells it at WARN once per interval;
 * nothing else is told as one.
 */
class LaterRecordsTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    private fun call(path: String, security: RequestSecurity): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest("GET", path, emptyMap(), null), security)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    /**
     * SNP-27: a record a later version wrote is the gap of a rolling upgrade, not a broken store: a 503 without detail,
     * never the 500 of a record that does not decode, whether its refusal comes wrapped or not, and the log says so with
     * the members its crit names.
     */
    @Test
    fun `a record a later version wrote is a 503 without detail, told as that`() {
        val id = runBlocking {
            fixture.changes.propose(
                fixture.plato,
                SnapshotKey.Platform,
                1,
                emptyList(),
                "later"
            )
        }.id
        fixture.later += id
        fixture.laterIndex[SnapshotKey.Tenant("acme")] = { it }
        val logger = LoggerFactory.getLogger("me.ahoo.cosec.control") as Logger
        val level = logger.level
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        logger.level = Level.DEBUG
        try {
            val change = call("/changes/${id.value}", plato)
            val revisions = call("/documents/tenant:acme/revisions", alice)
            for ((status, problem) in listOf(change, revisions)) {
                status.assert().isEqualTo(503)
                problem.get("code").asString().assert().isEqualTo("UNAVAILABLE")
                problem.has("detail").assert().isFalse()
            }
            val told = logged.captured().map { it.formattedMessage }.filter { it.contains("a later CoSec wrote") }
            told.assert().hasSize(2)
            told[0].assert().contains("GET /changes/${id.value}").contains("(the change record), members [device]")
            told[1].assert().contains("(the revision_index record), members [next]").contains("Upgrade this instance")
            logged.captured().filter { it.level == Level.ERROR }.assert().isEmpty()
        } finally {
            logger.level = level
            logger.detachAppender(logged)
        }
    }

    private val later = IllegalStateException(
        "decoding",
        SnapshotFormatException("later", StoredFormat.Later(StoredRecord.CHANGE, listOf("device"))),
    )

    /** However the refusal reaches the control API, wrapped as an argument error or as any failure, it is a 503. */
    @Test
    fun `a record a later version wrote is a 503 however its refusal comes wrapped`() {
        val wrappers = listOf<(SnapshotFormatException) -> Exception>(
            { IllegalArgumentException("decoding", it) },
            { IllegalStateException("decoding", it) },
        )
        for (wrapped in wrappers) {
            fixture.laterIndex[SnapshotKey.Tenant("acme")] = wrapped
            val (status, problem) = call("/documents/tenant:acme/revisions", alice)
            status.assert().isEqualTo(503)
            problem.get("code").asString().assert().isEqualTo("UNAVAILABLE")
            problem.has("detail").assert().isFalse()
        }
        // Without the marker, the same wrappers are what they were: the caller's 400, and a fault of this instance.
        fixture.laterIndex[SnapshotKey.Tenant("acme")] = { IllegalArgumentException("decoding") }
        call("/documents/tenant:acme/revisions", alice).first.assert().isEqualTo(400)
        fixture.laterIndex[SnapshotKey.Tenant("acme")] = { IllegalStateException("decoding") }
        call("/documents/tenant:acme/revisions", alice).first.assert().isEqualTo(500)
    }

    /** A later format names no member and, read where the kind of record is not known, no record. */
    @Test
    fun `a later format is told without members or a record`() {
        val logger = LoggerFactory.getLogger("me.ahoo.cosec.control") as Logger
        val level = logger.level
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        logger.level = Level.DEBUG
        try {
            val format = SnapshotFormatException("later", StoredFormat.Later(null, emptyList()))
            LaterRecords.told("call", format).assert().isTrue()
        } finally {
            logger.level = level
            logger.detachAppender(logged)
        }
        logged.captured().single().formattedMessage.assert().startsWith(
            "call is 503: a later CoSec wrote a stored record, which this instance does not read and never rewrites;"
        )
    }

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
