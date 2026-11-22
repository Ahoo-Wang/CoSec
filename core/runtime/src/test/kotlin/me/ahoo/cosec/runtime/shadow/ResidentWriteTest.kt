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

package me.ahoo.cosec.runtime.shadow

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `CTL-75`: the reporter writes an instance's resident documents before its status, only when what it holds differs
 * from what the last write that succeeded held, and in any case once half the record's life has passed since then. A
 * resident write that fails does not stop the status, and stays owed: every cycle tries it again until one succeeds.
 */
class ResidentWriteTest {
    private class MovableClock(var now: Instant = Instant.parse("2026-11-22T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    /** Every write in order, as `resident` or `status`; resident writes fail while [failing]. */
    private class Recording : ShadowReportSink {
        val writes = ArrayList<String>()
        val residents = ArrayList<ResidentDocuments>()
        var failing = false

        override suspend fun write(report: ShadowReport) = Unit

        override suspend fun write(status: InstanceStatus) {
            writes += "status"
        }

        override suspend fun write(resident: ResidentDocuments) {
            if (failing) error("Redis did not answer")
            writes += "resident"
            residents += resident
        }
    }

    private val clock = MovableClock()
    private val sink = Recording()
    private var holding: Map<SnapshotKey, Long> = mapOf(SnapshotKey.Platform to 3, SnapshotKey.Tenant("acme") to 7)
    private val reporter = ShadowReporter(ShadowRecorder("gw-1", clock), sink, { holding }, clock)

    private fun cycle(): List<String> = runBlocking {
        sink.writes.clear()
        reporter.flush()
        sink.writes.toList()
    }

    @Test
    fun `the resident documents are written before the status, and again only when they change`() {
        cycle().assert().containsExactly("resident", "status")
        sink.residents.single().assert().isEqualTo(ResidentDocuments("gw-1", clock.now, holding))
        clock.now = clock.now.plusSeconds(10)
        cycle().assert().describedAs("unchanged").containsExactly("status")
        holding = holding + (SnapshotKey.Tenant("acme") to 8L)
        clock.now = clock.now.plusSeconds(10)
        cycle().assert().describedAs("a new revision").containsExactly("resident", "status")
        sink.residents.last().documents.getValue(SnapshotKey.Tenant("acme")).assert().isEqualTo(8L)
        holding = holding - SnapshotKey.Tenant("acme")
        cycle().assert().describedAs("an eviction").containsExactly("resident", "status")
    }

    @Test
    fun `an unchanged resident record is written again once half its life has passed`() {
        cycle()
        val half = ShadowReportReader.RESIDENT_TTL.dividedBy(2)
        clock.now = clock.now.plus(half).minusMillis(1)
        cycle().assert().containsExactly("status")
        clock.now = clock.now.plusMillis(1)
        cycle().assert().containsExactly("resident", "status")
        // The half life counts from that write again.
        clock.now = clock.now.plus(half).minusMillis(1)
        cycle().assert().containsExactly("status")
    }

    @Test
    fun `a resident write that fails does not stop the status, and is tried again every cycle until it succeeds`() {
        sink.failing = true
        cycle().assert().containsExactly("status")
        clock.now = clock.now.plus(Duration.ofSeconds(10))
        // Nothing changed, but nothing was written either: it is still owed.
        cycle().assert().containsExactly("status")
        sink.failing = false
        cycle().assert().containsExactly("resident", "status")
        cycle().assert().containsExactly("status")
        // A change whose write fails stays owed too, though the next cycle holds the same.
        holding = holding + (SnapshotKey.Tenant("globex") to 1L)
        sink.failing = true
        cycle().assert().containsExactly("status")
        sink.failing = false
        cycle().assert().containsExactly("resident", "status")
        sink.residents.last().documents.keys.assert().contains(SnapshotKey.Tenant("globex"))
    }
}
