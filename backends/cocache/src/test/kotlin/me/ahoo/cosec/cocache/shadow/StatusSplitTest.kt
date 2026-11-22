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

package me.ahoo.cosec.cocache.shadow

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.cocache.snapshot.rawStore
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * `CTL-74`, `CTL-75` on CoCache: an instance's status is small and its resident documents are a record of their own.
 * What the acknowledgement reads does not grow with the documents an instance holds, the resident record lives exactly
 * its life, and a resident record that is missing or does not decode names its instance unreadable.
 */
class StatusSplitTest {
    /** The store's clock and the reader's, moved by the test. */
    private class Time : CacheClock {
        @Volatile
        var millis: Long = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli()

        override fun nowMillis(): Long = millis

        fun advance(by: Duration) {
            millis += by.toMillis()
        }

        val clock: Clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = Instant.ofEpochMilli(millis)
        }
    }

    /** Counts the bytes of every value read through it. */
    private class Metered<V : Any>(
        private val inner: Store<String, V>,
        private val codec: Codec<V>,
        private val bytes: AtomicLong,
        private val reads: AtomicInteger = AtomicInteger(),
    ) : Store<String, V> by inner {
        override fun async(): AsyncStore<String, V> = object : AsyncStore<String, V> by inner.async() {
            override fun getIfChanged(key: String, digest: Digest?): CompletableFuture<ConditionalGet<V>> =
                inner.async().getIfChanged(key, digest).thenApply { read ->
                    reads.incrementAndGet()
                    if (read is ConditionalGet.Present) bytes.addAndGet(codec.encode(read.value).size.toLong())
                    read
                }
        }
    }

    private val windows = (0 until 20).map { "c${7_350_000_000_000_000_000L + it}@${1_000_000 + it}" }.toSet()

    private fun documents(tenants: Int): Map<SnapshotKey, Long> = buildMap {
        put(SnapshotKey.Platform, 1_234_567)
        repeat(tenants) { put(SnapshotKey.Tenant("tenant-%09d".format(it)), 1_000_000L + it) }
    }

    /**
     * `CTL-74`: what the acknowledgement path reads per instance (its status and the list of instances) is the same for
     * an instance holding a hundred tenants as for one holding 16,384, for the same windows; it reads no resident record.
     */
    @Test
    fun `what the acknowledgement reads does not grow with the documents an instance holds`() = runBlocking<Unit> {
        val read = listOf(100, 16_384).map { tenants ->
            val keyPrefix = newKeyPrefix()
            SnapshotNode(InMemoryBackend(), keyPrefix).use { node ->
                val acknowledgement = AtomicLong()
                val residentReads = AtomicInteger()
                val factory = node.factory
                val reports = CoCacheShadowReports(
                    factory.createLocked(ShadowCacheSpecs.reports(keyPrefix)),
                    factory.createLocked(ShadowCacheSpecs.reporters(keyPrefix)),
                    Metered(
                        factory.createLocked(ShadowCacheSpecs.statuses(keyPrefix)),
                        InstanceStatusCodec,
                        acknowledgement
                    ),
                    Metered(
                        factory.createLocked(ShadowCacheSpecs.instances(keyPrefix)),
                        InstanceIndexCodec,
                        acknowledgement
                    ),
                    Metered(
                        factory.createLocked(ShadowCacheSpecs.residents(keyPrefix)),
                        ResidentDocumentsCodec,
                        AtomicLong(),
                        residentReads,
                    ),
                )
                val at = Instant.now()
                reports.write(ResidentDocuments("gw-1", at, documents(tenants)))
                reports.write(InstanceStatus("gw-1", at, windows = windows))
                reports.statusReadings().statuses.single().windows.assert().isEqualTo(windows)
                residentReads.get().assert().describedAs("resident reads by the acknowledgement").isZero()
                // Propagation reads it, and it holds what was written.
                reports.residents(
                    setOf("gw-1")
                ).residents.getValue("gw-1").documents.size.assert().isEqualTo(tenants + 1)
                acknowledgement.get()
            }
        }
        read[0].assert().isEqualTo(read[1])
    }

    /** `CTL-75`: a resident record lives exactly its hour after its last write, no jitter. */
    @Test
    fun `a resident record lives exactly its life after its last write`() = runBlocking<Unit> {
        val time = Time()
        val backend = InMemoryBackend(time)
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { node ->
            val reports = CoCacheShadowReports.create(node.factory, keyPrefix, time.clock)
            reports.write(ResidentDocuments("gw-1", time.clock.instant(), documents(3)))
            time.advance(ShadowCacheSpecs.RESIDENT_TTL.minusMillis(1))
            reports.residents(setOf("gw-1")).residents.keys.assert().containsExactly("gw-1")
            time.advance(Duration.ofMillis(1))
            reports.residents(setOf("gw-1")).unreadable.assert().containsExactly("gw-1")
        }
    }

    /**
     * `CTL-75`: a resident record that is missing or does not decode names its instance unreadable, while the others
     * are read; one under another instance's key is the store's data being wrong and fails the read.
     */
    @Test
    fun `a missing or undecodable resident record is unreadable, the undecodable one logged, another's fails the read`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { node ->
            val reports = CoCacheShadowReports.create(node.factory, keyPrefix, Clock.systemUTC())
            val at = Instant.now()
            reports.write(ResidentDocuments("i0", at, documents(2)))
            reports.write(ResidentDocuments("i1", at, documents(2)))
            SnapshotNode(backend, keyPrefix).use { raw ->
                raw.runtime.create(
                    rawStore(
                        "RawResident",
                        keyPrefix,
                        CoSecCacheSpecs.INSTANCE_RESIDENT_CACHE_NAME,
                        KeyEncoder<String> { it }
                    )
                ).async().put("i1", """{"format":9}""".encodeToByteArray()).await()
            }
            val lines = CopyOnWriteArrayList<ILoggingEvent>()
            val appender = object : AppenderBase<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) {
                    if ("resident documents" in event.formattedMessage) lines += event
                }
            }.also { it.start() }
            val logger = LoggerFactory.getLogger(CoCacheShadowReports::class.java) as Logger
            logger.addAppender(appender)
            val readings = try {
                reports.residents(setOf("i0", "i1", "i2"))
            } finally {
                logger.detachAppender(appender)
            }
            readings.residents.keys.assert().containsExactly("i0")
            readings.unreadable.assert().containsExactlyInAnyOrder("i1", "i2")
            // The record that does not decode is named at WARN, with why; the missing one (i2) is expected, and quiet.
            lines.single().level.assert().isEqualTo(Level.WARN)
            lines.single().formattedMessage.assert().contains("instance [i1]").contains("format").doesNotContain("[i2]")
            SnapshotNode(backend, keyPrefix).use { other ->
                other.runtime.create(ShadowCacheSpecs.residents(keyPrefix)).async()
                    .put("i0", ResidentDocuments("i9", at, documents(1))).await()
            }
            assertThrownBy<IllegalStateException> { runBlocking { reports.residents(setOf("i0")) } }
            assertThrownBy<IllegalArgumentException> {
                runBlocking {
                    reports.write(
                        ResidentDocuments("x:y", at, emptyMap())
                    )
                }
            }
        }
    }
}
