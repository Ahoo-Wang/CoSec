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

package me.ahoo.cosec.cache.shadow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.createLocked
import me.ahoo.cosec.cache.snapshot.RedisTarget
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.cache.snapshot.newKeyPrefix
import me.ahoo.cosec.runtime.shadow.InstalledDocument
import me.ahoo.cosec.runtime.shadow.InstanceStatus
import me.ahoo.cosec.runtime.shadow.ShadowCodec
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.ShadowSample
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
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
        val control = node().let { CoCacheControlStore.create(it.factory, it.cache, keyPrefix) }
        val instance = CoCacheCandidateStore.create(node().factory, keyPrefix)
        instance.candidate(key).assert().isNull()
        val body = """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}"""
        val document = SnapshotDocument(2, 5, Instant.parse("2026-11-01T00:00:00Z"), body.encodeToByteArray())
        control.putCandidate(key, null, StoredCandidate("c1@1", 4, document)).assert().isTrue()
        val read = instance.candidate(key)!!
        read.window.assert().isEqualTo("c1@1")
        read.baseRevision.assert().isEqualTo(4)
        read.document.assert().isEqualTo(document)
        control.candidate(key)!!.window.assert().isEqualTo("c1@1")
        // Only the candidate of the window named: another window's stays.
        control.withdrawCandidate(key, "c0@1").assert().isFalse()
        instance.candidate(key)!!.window.assert().isEqualTo("c1@1")
        control.withdrawCandidate(key, "c1@1").assert().isTrue()
        instance.candidate(key).assert().isNull()
        control.withdrawCandidate(key, "c1@1").assert().isFalse()
    }

    @Test
    fun `reports written at once by many instances are all kept`() = runBlocking<Unit> {
        val sinks = (1..4).map { CoCacheShadowReports.create(node().factory, keyPrefix, clock) }
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

    /** Round-2 review R1: each instance's report has a key of its own, which an ACL can confine that instance to. */
    @Test
    fun `each instance's report has its own key, and one under another's key is not counted`() = runBlocking<Unit> {
        val sink = CoCacheShadowReports.create(node().factory, keyPrefix, clock)
        sink.write(report("i0", 1))
        sink.write(report("i1", 2))
        // Read directly, from another node: one report per key, `<p>:shadow:report:<window>:<instance>`.
        val raw = node().runtime
        val reports = raw.create(ShadowCacheSpecs.reports(keyPrefix))
        val reporters = raw.create(ShadowCacheSpecs.reporters(keyPrefix))
        reports.async().get("c1@1:i0").await()!!.counts.getValue(ShadowOutcome.NARROWED).assert().isEqualTo(1L)
        reports.async().get("c1@1:i1").await()!!.counts.getValue(ShadowOutcome.NARROWED).assert().isEqualTo(2L)
        reporters.async().get("c1@1").await().assert().isEqualTo(setOf("i0", "i1"))
        // An instance whose entry was dropped from the window's reporters is back with its next report (the control
        // plane, which keeps every instance it has seen, refuses to close the window in the meantime).
        reporters.async().put("c1@1", setOf("i0")).await()
        sink.reports("c1@1").map { it.instance }.assert().containsExactly("i0")
        sink.write(report("i1", 3))
        sink.reports("c1@1").associate { it.instance to it.counts.getValue(ShadowOutcome.NARROWED) }.assert()
            .isEqualTo(mapOf("i0" to 1L, "i1" to 3L))
        // A report under one instance's key that says it is another's is an error, never a sample.
        reports.async().put("c1@1:i1", report("i2", 9)).await()
        assertThrownBy<IllegalStateException> { runBlocking { sink.reports("c1@1") } }
        // So is a listed instance whose report is gone: the instance that vanishes may be the one that saw errors.
        reports.async().delete("c1@1:i1").await()
        assertThrownBy<IllegalStateException> { runBlocking { sink.reports("c1@1") } }
        // An id that would make a key ambiguous, or a negative count, is never written.
        listOf(report("x:i3", 1), report("i3", -1), report("i3", 1).copy(window = "c1@1:i0")).forEach { bad ->
            assertThrownBy<IllegalArgumentException> { runBlocking { sink.write(bad) } }
        }
    }

    @Test
    fun `statuses older than a minute are not counted`() = runBlocking<Unit> {
        val sink = CoCacheShadowReports.create(node().factory, keyPrefix, clock)
        val installed = mapOf(key to InstalledDocument(5, Duration.ofSeconds(2)))
        sink.write(InstanceStatus("old", clock.now, installed))
        clock.now = clock.now.plusSeconds(45)
        sink.write(InstanceStatus("new", clock.now, installed))
        sink.statuses().map { it.instance }.toSet().assert().isEqualTo(setOf("old", "new"))
        clock.now = clock.now.plusSeconds(30)
        sink.statuses().single().instance.assert().isEqualTo("new")
        sink.statuses().single().documents.assert().isEqualTo(installed)
    }

    /** What reached Redis from the stores of a [Metered] sink: how many values were written and how many bytes. */
    private class Meter {
        var writes = 0
        var written = 0
        var fullReads = 0
        var readBytes = 0

        fun reset() {
            writes = 0
            written = 0
            fullReads = 0
            readBytes = 0
        }
    }

    private class Metered<V : Any>(
        private val inner: Store<String, V>,
        private val codec: Codec<V>,
        private val meter: Meter
    ) : Store<String, V> by inner {
        override fun async(): AsyncStore<String, V> = object : AsyncStore<String, V> by inner.async() {
            override fun put(key: String, value: V) = inner.async().put(key, value).also {
                meter.writes++
                meter.written += codec.encode(value).size
            }

            override fun putIfMatch(key: String, digest: Digest?, value: V) =
                inner.async().putIfMatch(key, digest, value).also {
                    meter.writes++
                    meter.written += codec.encode(value).size
                }

            override fun getIfChanged(key: String, digest: Digest?) =
                inner.async().getIfChanged(key, digest).thenApply { read ->
                    if (read is ConditionalGet.Present) {
                        meter.fullReads++
                        meter.readBytes += codec.encode(read.value).size
                    }
                    read
                }
        }
    }

    private fun metered(meter: Meter): CoCacheShadowReports {
        val factory = node().factory
        return CoCacheShadowReports(
            factory.createLocked(ShadowCacheSpecs.reports(keyPrefix)),
            factory.createLocked(ShadowCacheSpecs.reporters(keyPrefix)),
            Metered(factory.createLocked(ShadowCacheSpecs.statuses(keyPrefix)), InstanceStatusCodec, meter),
            Metered(factory.createLocked(ShadowCacheSpecs.instances(keyPrefix)), InstanceIndexCodec, meter),
            clock,
        )
    }

    /** Ids of one length, so that every status encodes to the same size. */
    private fun id(i: Int) = "i" + i.toString().padStart(2, '0')

    private fun installed(documents: Int) =
        (1..documents).associate { SnapshotKey.Tenant("t$it") as SnapshotKey to InstalledDocument(42, Duration.ZERO) }

    /**
     * #1214: a heartbeat writes the instance's own status and asks whether the list of instances changed, which costs
     * no value while it did not. Before, every heartbeat read and wrote every instance's status in one value.
     */
    @Test
    fun `a heartbeat writes one status however many instances there are - #1214`() = runBlocking<Unit> {
        val documents = installed(DOCUMENTS)
        val one = InstanceStatusCodec.encode(InstanceStatus(id(0), clock.now, documents)).size
        val sizes = listOf(2, 16).map { instances ->
            val meter = Meter()
            val sinks = (0 until instances).map { metered(meter) }
            repeat(3) {
                if (it == 2) meter.reset()
                sinks.forEachIndexed { i, sink -> sink.write(InstanceStatus(id(i), clock.now, documents)) }
            }
            // Steady state: one status written per heartbeat, and the list unchanged, so no value read.
            meter.writes.assert().isEqualTo(instances)
            meter.written.assert().isEqualTo(instances * one)
            meter.fullReads.assert().isEqualTo(0)
            sinks.first().statuses().map { it.instance }.toSet().assert().isEqualTo(
                (0 until instances).map(::id).toSet()
            )
            val shared = ShadowCodec.encodeStatuses(
                (0 until instances).associate { id(it) to InstanceStatus(id(it), clock.now, documents) }
            ).size
            meter.written / instances to shared
        }
        // What the heartbeat of one instance wrote, per instance: the same for 2 and 16; the shared value grew with them.
        sizes[0].first.assert().isEqualTo(sizes[1].first)
        (sizes[1].second > 4 * sizes[0].second).assert().isTrue()
    }

    @Test
    fun `an instance lists itself again when the list is lost, refreshes its entry, and drops the instances gone - #1214`() =
        runBlocking<Unit> {
            val raw = node().runtime.create(ShadowCacheSpecs.instances(keyPrefix))
            val documents = installed(1)
            val live = CoCacheShadowReports.create(node().factory, keyPrefix, clock)
            val gone = CoCacheShadowReports.create(node().factory, keyPrefix, clock)
            fun status(instance: String) = InstanceStatus(instance, clock.now, documents)

            live.write(status("live"))
            gone.write(status("gone"))
            live.write(status("live"))
            live.statuses().map { it.instance }.toSet().assert().isEqualTo(setOf("live", "gone"))

            // The list is lost (a Redis restart): the next heartbeat lists the instance again, and only it.
            raw.async().delete(ShadowCacheSpecs.INDEX_KEY).await()
            live.statuses().assert().isEmpty()
            live.write(status("live"))
            live.statuses().map { it.instance }.assert().containsExactly("live")
            gone.write(status("gone"))
            live.statuses().map { it.instance }.toSet().assert().isEqualTo(setOf("live", "gone"))

            // An entry nobody refreshed for the expiry is dropped by the next write of the list; a refreshed one stays.
            clock.now = clock.now.plus(ShadowCacheSpecs.INDEX_REFRESH).plusSeconds(1)
            live.write(status("live"))
            raw.async().get(
                ShadowCacheSpecs.INDEX_KEY
            ).await()!!.keys.assert().containsExactlyInAnyOrder("live", "gone")
            clock.now = clock.now.plus(ShadowCacheSpecs.INDEX_REFRESH).plusSeconds(1)
            live.write(status("live"))
            clock.now = clock.now.plus(ShadowCacheSpecs.INDEX_REFRESH).plusSeconds(1)
            live.write(status("live"))
            raw.async().get(ShadowCacheSpecs.INDEX_KEY).await()!!.keys.assert().containsExactly("live")
        }

    @Test
    fun `a status that is another instance's, or cannot be read, fails the read of statuses - #1214`() = runBlocking<Unit> {
        val sink = CoCacheShadowReports.create(node().factory, keyPrefix, clock)
        val statuses = node().runtime.create(ShadowCacheSpecs.statuses(keyPrefix))
        sink.write(InstanceStatus("a", clock.now, installed(1)))
        sink.write(InstanceStatus("b", clock.now, installed(1)))
        statuses.async().put("b", InstanceStatus("a", clock.now, installed(1))).await()
        assertThrownBy<IllegalStateException> { runBlocking { sink.statuses() } }
        // A listed instance whose status expired is gone, not an error.
        statuses.async().delete("b").await()
        sink.statuses().map { it.instance }.assert().containsExactly("a")
        val ambiguous = InstanceStatus("x:y", clock.now, installed(1))
        assertThrownBy<IllegalArgumentException> { runBlocking { sink.write(ambiguous) } }
    }

    private companion object {
        const val DOCUMENTS = 50
    }
}
