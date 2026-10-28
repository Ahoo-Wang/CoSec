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
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** RFC 0007 §6.4, §6.5: the candidate source, the recorder and reporter, the in-memory reports and their JSON. */
class ShadowSupportTest {
    private class MovableClock(var now: Instant = Instant.parse("2026-11-01T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock()
    private val acme: SnapshotKey = SnapshotKey.Tenant("acme")
    private val document = SnapshotDocument(
        2,
        5,
        Instant.parse("2026-11-01T00:00:00Z"),
        """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray(),
    )
    private val compiled = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList())

    @Test
    fun `the candidate source caches, refreshes, evicts and fails quietly`() = runBlocking<Unit> {
        val reads = AtomicInteger()
        var stored: StoredCandidate? = StoredCandidate("w", 4, document)
        var failing = false
        val store = CandidateStore {
            reads.incrementAndGet()
            check(!failing) { "the store is down" }
            if (it == acme) stored else null
        }
        val source = CachingCandidateSource(
            store,
            { _, _ -> compiled },
            maxEntries = 1,
            refreshAfter = Duration.ofSeconds(10),
            clock = clock
        )
        (source.candidate(acme) as Candidate.Ready).baseRevision.assert().isEqualTo(4)
        source.candidate(acme)
        reads.get().assert().isEqualTo(1)
        // Read again once it is old; an absent candidate is cached too.
        stored = null
        clock.now = clock.now.plusSeconds(10)
        source.candidate(acme).assert().isNull()
        reads.get().assert().isEqualTo(2)
        // One entry: another key evicts acme's.
        source.candidate(SnapshotKey.Platform).assert().isNull()
        source.candidate(acme)
        reads.get().assert().isEqualTo(4)
        // A failing read answers no candidate and is counted.
        failing = true
        clock.now = clock.now.plusSeconds(10)
        source.candidate(acme).assert().isNull()
        source.failures.assert().isEqualTo(1)
    }

    @Test
    fun `a candidate that does not compile is a failed one`() = runBlocking<Unit> {
        val source = CachingCandidateSource(
            { StoredCandidate("w", 4, document) },
            { _, _ -> error("no such attribute") }
        )
        (source.candidate(acme) as Candidate.Failed).window.assert().isEqualTo("w")
    }

    @Test
    fun `the recorder keeps counts per window and at most twenty samples`() {
        val recorder = ShadowRecorder("i1", clock)
        val sample = ShadowSample("acme", "bob", "order", "", "http:GET", "http//orders", "a[0]", null)
        repeat(25) { recorder.record("w", ShadowOutcome.NARROWED, 4, sample) }
        recorder.record("w", ShadowOutcome.SAME, 4, null)
        recorder.record("v", ShadowOutcome.BASE_MISMATCH, 3, null)
        val w = recorder.reports().single { it.window == "w" }
        w.counts.assert().isEqualTo(mapOf(ShadowOutcome.NARROWED to 25L, ShadowOutcome.SAME to 1L))
        w.samples.getValue(ShadowOutcome.NARROWED).size.assert().isEqualTo(ShadowReport.MAX_SAMPLES)
        w.baseRevisions.assert().isEqualTo(setOf(4L))
        w.first.assert().isEqualTo(clock.now)
        recorder.forget("w")
        recorder.reports().single().window.assert().isEqualTo("v")
        assertThrownBy<IllegalArgumentException> { ShadowRecorder("") }
        // An instance id ends its report's key: a ':' would let it match another instance's key or ACL pattern.
        listOf("x:pod-a", "pod a", "pod/a", "pod*").forEach { id ->
            assertThrownBy<IllegalArgumentException> { ShadowRecorder(id) }
        }
        ShadowRecorder("pod-a.eu_1").instance.assert().isEqualTo("pod-a.eu_1")
    }

    @Test
    fun `the reporter writes reports and the status, and a failing sink costs only the cycle`() = runBlocking<Unit> {
        val recorder = ShadowRecorder("i1", clock)
        recorder.record("w", ShadowOutcome.WIDENED, 4, null)
        val reports = InMemoryShadowReports(clock)
        val installed = mapOf(acme to InstalledDocument(4, Duration.ofSeconds(1)))
        ShadowReporter(recorder, reports, { installed }, clock).flush()
        reports.reports("w").single().counts.assert().isEqualTo(mapOf(ShadowOutcome.WIDENED to 1L))
        reports.statuses().single().documents.assert().isEqualTo(installed)
        clock.now = clock.now.plus(ShadowReportReader.STATUS_TTL).plusSeconds(1)
        reports.statuses().assert().isEmpty()
        val failing = object : ShadowReportSink {
            override suspend fun write(report: ShadowReport) = error("down")

            override suspend fun write(status: InstanceStatus) = error("down")
        }
        ShadowReporter(recorder, failing, { installed }, clock).flush()
        assertThrownBy<IllegalArgumentException> {
            ShadowReporter(
                recorder,
                reports,
                { installed },
                clock,
                Duration.ZERO
            )
        }
    }

    @Test
    fun `candidates, reports and statuses read back as they were written`() {
        val candidate = ShadowCodec.decodeCandidate(ShadowCodec.encode(StoredCandidate("w", 4, document)))
        candidate.window.assert().isEqualTo("w")
        candidate.baseRevision.assert().isEqualTo(4)
        candidate.document.revision.assert().isEqualTo(5)
        val report = ShadowReport(
            "w",
            "i1",
            mapOf(ShadowOutcome.NARROWED to 2L),
            mapOf(
                ShadowOutcome.NARROWED to listOf(ShadowSample("acme", "bob", "order", "", "http:GET", "http//orders", "a[0]", null))
            ),
            setOf(4L),
            clock.now,
            null,
        )
        ShadowCodec.decodeReport(ShadowCodec.encodeReport(report)).assert().isEqualTo(report)
        val status = InstanceStatus(
            "i1",
            clock.now,
            mapOf(
                acme to InstalledDocument(4, Duration.ofMillis(250)),
                SnapshotKey.Platform to InstalledDocument(9, Duration.ZERO)
            )
        )
        ShadowCodec.decodeStatuses(
            ShadowCodec.encodeStatuses(mapOf("i1" to status))
        ).assert().isEqualTo(mapOf("i1" to status))
        listOf("{}", "[]", "not json", """{"window":"w","baseRevision":"x"}""").forEach { text ->
            assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeCandidate(text.encodeToByteArray()) }
        }
        assertThrownBy<SnapshotFormatException> {
            ShadowCodec.decodeReport("""{"window":"w"}""".encodeToByteArray())
        }
        // A negative count would cancel another instance's when the reports are summed; a ':' in an instance id
        // would make its key ambiguous.
        val valid = String(ShadowCodec.encodeReport(report))
        listOf(
            valid.replace(""""NARROWED":2""", """"NARROWED":-2"""),
            valid.replace(""""instance":"i1"""", """"instance":"x:i1""""),
        ).forEach { text ->
            text.assert().isNotEqualTo(valid)
            assertThrownBy<SnapshotFormatException> { ShadowCodec.decodeReport(text.encodeToByteArray()) }
        }
        assertThrownBy<SnapshotFormatException> {
            ShadowCodec.decodeStatuses(
                """{"i1":{"at":"x","documents":{}}}""".encodeToByteArray()
            )
        }
    }
}
