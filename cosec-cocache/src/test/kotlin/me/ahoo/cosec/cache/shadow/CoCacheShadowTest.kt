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

package me.ahoo.cosec.cache.shadow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.snapshot.RedisTarget
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.cache.snapshot.newKeyPrefix
import me.ahoo.cosec.runtime.shadow.InstalledDocument
import me.ahoo.cosec.runtime.shadow.InstanceStatus
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.ShadowSample
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * RFC 0007 §6.4, §6.5 on real Redis (AGENTS.md: no mocks for caching and concurrency): the control plane's candidate is
 * what every instance reads, every instance's report of a window is kept when they write at once, and statuses older
 * than a minute are not counted.
 */
class CoCacheShadowTest {
    private val backend: CoherenceBackend = RedisTarget.REDIS.backend()
    private val keyPrefix = newKeyPrefix()
    private val nodes = ArrayList<SnapshotNode>()
    private val key: SnapshotKey = SnapshotKey.Tenant("acme")

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock(Instant.parse("2026-11-01T00:00:00Z"))

    @AfterEach
    fun close() {
        nodes.forEach(SnapshotNode::close)
        backend.close()
    }

    private fun node() = SnapshotNode(backend, keyPrefix).also { nodes += it }

    private fun report(instance: String, narrowed: Long) = ShadowReport(
        window = "c1@1",
        instance = instance,
        counts = mapOf(ShadowOutcome.SAME to 3L, ShadowOutcome.NARROWED to narrowed),
        samples = mapOf(
            ShadowOutcome.NARROWED to listOf(ShadowSample("acme", "bob", "order", "", "http:GET", "http//orders/1", "read[0]", null))
        ),
        baseRevisions = setOf(4L),
        first = clock.now,
        last = clock.now,
    )

    @Test
    fun `the control plane's candidate is what an instance reads, until it is withdrawn`() = runBlocking<Unit> {
        val control = node().let { CoCacheControlStore.create(it.runtime, it.cache, keyPrefix) }
        val instance = CoCacheCandidateStore.create(node().runtime, keyPrefix)
        instance.candidate(key).assert().isNull()
        val body = """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}"""
        val document = SnapshotDocument(2, 5, Instant.parse("2026-11-01T00:00:00Z"), body.encodeToByteArray())
        control.putCandidate(key, StoredCandidate("c1@1", 4, document))
        val read = instance.candidate(key)!!
        read.window.assert().isEqualTo("c1@1")
        read.baseRevision.assert().isEqualTo(4)
        read.document.assert().isEqualTo(document)
        control.candidate(key)!!.window.assert().isEqualTo("c1@1")
        control.putCandidate(key, null)
        instance.candidate(key).assert().isNull()
    }

    @Test
    fun `reports written at once by many instances are all kept`() = runBlocking<Unit> {
        val sinks = (1..4).map { CoCacheShadowReports.create(node().runtime, keyPrefix, clock) }
        sinks.mapIndexed { i, sink ->
            async {
                repeat(
                    3
                ) { round -> sink.write(report("i$i", round + 1L)) }
            }
        }.awaitAll()
        val reports = sinks.first().reports("c1@1")
        reports.map { it.instance }.toSet().assert().isEqualTo(setOf("i0", "i1", "i2", "i3"))
        reports.forEach { it.counts.getValue(ShadowOutcome.NARROWED).assert().isEqualTo(3L) }
        reports.first().samples.getValue(ShadowOutcome.NARROWED).single().principal.assert().isEqualTo("bob")
        sinks.first().reports("another").assert().isEmpty()
    }

    @Test
    fun `statuses older than a minute are not counted`() = runBlocking<Unit> {
        val sink = CoCacheShadowReports.create(node().runtime, keyPrefix, clock)
        val installed = mapOf(key to InstalledDocument(5, Duration.ofSeconds(2)))
        sink.write(InstanceStatus("old", clock.now, installed))
        clock.now = clock.now.plusSeconds(45)
        sink.write(InstanceStatus("new", clock.now, installed))
        sink.statuses().map { it.instance }.toSet().assert().isEqualTo(setOf("old", "new"))
        clock.now = clock.now.plusSeconds(30)
        sink.statuses().single().instance.assert().isEqualTo("new")
        sink.statuses().single().documents.assert().isEqualTo(installed)
    }
}
