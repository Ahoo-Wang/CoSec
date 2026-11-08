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

package me.ahoo.cosec.runtime.shadow

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1210: an instance forgets a shadow window once its candidate is no longer staged and its last report is written,
 * and writes it no more. A window still staged keeps its cumulative counts, a last write that fails is written again,
 * and a sample that comes in meanwhile is in the report written after it.
 */
class ShadowRetentionTest {
    private class MovableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock()
    private val document = SnapshotDocument(
        2,
        5,
        Instant.parse("2026-01-01T00:00:00Z"),
        """{"schema":2,"revision":5,"publishedAt":"2026-01-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray(),
    )

    /** The `candidate:` namespace as the control plane leaves it. */
    private val staged = HashMap<SnapshotKey, StoredCandidate>()
    private val store = object : CandidateStore {
        var failing = false

        override suspend fun candidate(key: SnapshotKey): StoredCandidate? {
            check(!failing) { "the candidate store is down" }
            return staged[key]
        }
    }

    private val sink = object : ShadowReportSink {
        val written = ArrayList<ShadowReport>()
        var failing = false
        var onWrite: (ShadowReport) -> Unit = {}

        override suspend fun write(report: ShadowReport) {
            check(!failing) { "the report store is down" }
            written += report
            onWrite(report)
        }

        override suspend fun write(status: InstanceStatus) = Unit
    }

    private val recorder = ShadowRecorder("i1", clock)
    private val reporter = ShadowReporter(recorder, sink, { emptyMap() }, clock, candidates = store)

    private fun tenant(i: Int): SnapshotKey = SnapshotKey.Tenant("t$i")

    private fun stage(key: SnapshotKey, window: String) {
        staged[key] = StoredCandidate(window, 4, document)
    }

    private fun flush(): List<ShadowReport> {
        sink.written.clear()
        runBlocking { reporter.flush() }
        return sink.written.toList()
    }

    @Test
    fun `windows whose candidates are withdrawn are written once more, then never - #1210`() {
        repeat(1000) { i ->
            stage(tenant(i), "w$i")
            recorder.record(tenant(i), "w$i", ShadowOutcome.SAME, 4, null)
        }
        flush().size.assert().isEqualTo(1000)
        staged.clear()
        // Their last reports, then nothing: nine days on, no window is written again, and none is kept.
        flush().size.assert().isEqualTo(1000)
        clock.now = clock.now.plus(Duration.ofDays(9))
        flush().assert().isEmpty()
        recorder.reports().assert().isEmpty()
    }

    @Test
    fun `a window still staged keeps its cumulative counts`() {
        val key = tenant(1)
        stage(key, "w")
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        recorder.record(key, "w", ShadowOutcome.NARROWED, 4, null)
        flush().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L, ShadowOutcome.NARROWED to 1L))
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        flush().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.SAME to 2L, ShadowOutcome.NARROWED to 1L))
        flush().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.SAME to 2L, ShadowOutcome.NARROWED to 1L))
    }

    @Test
    fun `a window whose document stages another window has ended`() {
        val key = tenant(1)
        stage(key, "old")
        recorder.record(key, "old", ShadowOutcome.SAME, 4, null)
        flush()
        stage(key, "new")
        recorder.record(key, "new", ShadowOutcome.WIDENED, 4, null)
        flush().map { it.window }.assert().containsExactlyInAnyOrder("old", "new")
        flush().map { it.window }.assert().containsExactly("new")
    }

    @Test
    fun `a last report that cannot be written is written again, then the window is forgotten`() {
        val key = tenant(1)
        stage(key, "w")
        recorder.record(key, "w", ShadowOutcome.CANDIDATE_ERROR, 4, null)
        flush()
        staged.clear()
        sink.failing = true
        flush()
        recorder.reports().map { it.window }.assert().containsExactly("w")
        sink.failing = false
        flush().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.CANDIDATE_ERROR to 1L))
        flush().assert().isEmpty()
    }

    @Test
    fun `a window is not forgotten while its candidate cannot be read`() {
        val key = tenant(1)
        stage(key, "w")
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        staged.clear()
        store.failing = true
        flush().single().window.assert().isEqualTo("w")
        flush().single().window.assert().isEqualTo("w")
        store.failing = false
        flush().single().window.assert().isEqualTo("w")
        flush().assert().isEmpty()
    }

    @Test
    fun `a sample recorded while the last report is written is in the report written after it`() {
        val key = tenant(1)
        stage(key, "w")
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        flush()
        staged.clear()
        // A listener that read the candidate before it was withdrawn records while the reporter writes.
        sink.onWrite = { recorder.record(key, "w", ShadowOutcome.NARROWED, 4, null) }
        flush().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L))
        sink.onWrite = {}
        flush().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L, ShadowOutcome.NARROWED to 1L))
        flush().assert().isEmpty()
    }

    @Test
    fun `a sample of a forgotten window is dropped, not written as a new report`() {
        val key = tenant(1)
        stage(key, "w")
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        staged.clear()
        flush().single().window.assert().isEqualTo("w")
        // A listener's cached candidate may still name the window for a while.
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        flush().assert().isEmpty()
        recorder.reports().assert().isEmpty()
    }

    @Test
    fun `samples of a forgotten window are dropped only for the retention of ended windows`() {
        val key = tenant(1)
        val recorder = ShadowRecorder("i1", clock, endedRetention = Duration.ofMinutes(10))
        recorder.forget("absent", 0).assert().isFalse()
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        recorder.forget("w", 1).assert().isTrue()
        recorder.record(key, "w", ShadowOutcome.SAME, 4, null)
        recorder.reports().assert().isEmpty()
        // Forgetting another window later clears the ended windows older than the retention.
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        recorder.record(key, "v", ShadowOutcome.SAME, 4, null)
        recorder.forget("v", 1).assert().isTrue()
        recorder.record(key, "w", ShadowOutcome.NARROWED, 4, null)
        recorder.reports().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.NARROWED to 1L))
        assertThrownBy<IllegalArgumentException> {
            ShadowRecorder(
                "i1",
                clock,
                endedRetention = Duration.ofSeconds(-1)
            )
        }
    }

    @Test
    fun `without a candidate store windows are kept as before`() {
        val kept = ShadowReporter(recorder, sink, { emptyMap() }, clock)
        recorder.record(tenant(1), "w", ShadowOutcome.SAME, 4, null)
        repeat(2) {
            sink.written.clear()
            runBlocking { kept.flush() }
            sink.written.single().window.assert().isEqualTo("w")
        }
    }
}
