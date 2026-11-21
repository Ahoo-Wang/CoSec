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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.cocache.snapshot.rawStore
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `CTL-73`: a listed status that does not decode names its instance unreadable while every other status is read; a
 * status under another instance's key, and a store that does not answer, still fail the whole read.
 */
class StatusReadingsTest {
    private fun status(instance: String, of: String = instance) =
        InstanceStatus(of, Clock.systemUTC().instant(), emptyMap(), windows = setOf("c1@1"))

    @Test
    fun `a status that does not decode names its instance, and every other status is read`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { node ->
            val reports = CoCacheShadowReports.create(node.factory, keyPrefix, Clock.systemUTC())
            listOf("i0", "i1", "i2").forEach { reports.write(status(it)) }
            reports.statusReadings().unreadable.assert().isEmpty()
            SnapshotNode(backend, keyPrefix).use { raw ->
                raw.runtime.create(
                    rawStore(
                        "RawStatus",
                        keyPrefix,
                        CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME,
                        KeyEncoder<String> { it }
                    )
                ).async().put("i1", """{"format":9,"at":"x"}""".encodeToByteArray()).await()
            }
            val readings = reports.statusReadings()
            readings.statuses.map { it.instance }.assert().containsExactlyInAnyOrder("i0", "i2")
            readings.unreadable.assert().containsExactly("i1")
            // The call that reads every status still fails on it, as before.
            runCatching { reports.statuses() }.exceptionOrNull().assert().isInstanceOf(
                SnapshotFormatException::class.java
            )
        }
    }

    /** The store's clock and the reader's, moved by the test: an entry expires exactly when the test says. */
    private class Time : CacheClock {
        @Volatile
        var millis: Long = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli()

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

    /** Writes [bytes] as [instance]'s status, on a node of its own, with the key's life [ttl] when given. */
    private suspend fun putRaw(
        backend: InMemoryBackend,
        keyPrefix: String,
        instance: String,
        bytes: String,
        ttl: Duration? = null,
    ) = SnapshotNode(backend, keyPrefix).use { raw ->
        val store = rawStore(
            "RawStatus",
            keyPrefix,
            CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME,
            KeyEncoder<String> { it },
            ttl,
        )
        raw.runtime.create(store).async().put(instance, bytes.encodeToByteArray()).await()
    }

    private fun status(instance: String, time: Time) =
        InstanceStatus(instance, time.clock.instant(), emptyMap(), windows = setOf("c1@1"))

    /**
     * An undecodable status has no `at` to age by: the store ends it. A status is written with the key's life, two
     * minutes renewed by each write, so once its instance stops writing the value expires, readable or not, and the
     * instance is no longer named, however often the others write.
     */
    @Test
    fun `an unreadable status is no longer named once its key expires, two minutes after its last write`() =
        runBlocking<Unit> {
            val time = Time()
            val backend = InMemoryBackend(time)
            val keyPrefix = newKeyPrefix()
            SnapshotNode(backend, keyPrefix).use { node ->
                val reports = CoCacheShadowReports.create(node.factory, keyPrefix, time.clock)
                reports.write(status("i0", time))
                reports.write(status("i1", time))
                // i1's last write does not decode; it is written as every status is, with the key's life.
                putRaw(backend, keyPrefix, "i1", """{"format":9}""", ShadowCacheSpecs.STATUS_TTL)
                reports.statusReadings().unreadable.assert().containsExactly("i1")
                // The held clock decides, to the millisecond: named until the key's life ends, and not from then on.
                time.advance(ShadowCacheSpecs.STATUS_TTL.minusMillis(1))
                reports.write(status("i0", time))
                reports.statusReadings().unreadable.assert().describedAs("a millisecond before").containsExactly("i1")
                time.advance(Duration.ofMillis(1))
                val readings = reports.statusReadings()
                readings.unreadable.assert().describedAs("at the end of the key's life").isEmpty()
                readings.statuses.map { it.instance }.assert().containsExactly("i0")
            }
        }

    /**
     * What ends an undecodable status is its key's life, so that life is exact: a status written through the store
     * CoSec creates is there a millisecond before [ShadowCacheSpecs.STATUS_TTL] after its last write and gone at it,
     * with no jitter to stretch it (CoCache's default would make it anything from one minute to three).
     */
    @Test
    fun `a status's key lives exactly two minutes after its last write`() = runBlocking<Unit> {
        val time = Time()
        val backend = InMemoryBackend(time)
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { node ->
            val reports = CoCacheShadowReports.create(node.factory, keyPrefix, time.clock)
            SnapshotNode(backend, keyPrefix).use { raw ->
                val stored = raw.runtime.create(
                    rawStore(
                        "RawStatus",
                        keyPrefix,
                        CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME,
                        KeyEncoder<String> { it }
                    )
                )
                suspend fun present() = stored.async().getIfChanged("i1", null).await() is ConditionalGet.Present
                // Each write renews the life: the second one counts.
                reports.write(status("i1", time))
                time.advance(Duration.ofSeconds(30))
                reports.write(status("i1", time))
                time.advance(ShadowCacheSpecs.STATUS_TTL.minusMillis(1))
                present().assert().describedAs("a millisecond before").isTrue()
                time.advance(Duration.ofMillis(1))
                present().assert().describedAs("at the end of its life").isFalse()
            }
        }
    }

    /**
     * A value with no life, which CoSec never writes (one set by hand), counts only while its instance's entry in the
     * list is current: a gone instance stops refreshing it, so after the list's expiry the instance is not named.
     */
    @Test
    fun `an unreadable status without a life is not named once its instance's entry in the list is old`() =
        runBlocking<Unit> {
            val time = Time()
            val backend = InMemoryBackend(time)
            val keyPrefix = newKeyPrefix()
            SnapshotNode(backend, keyPrefix).use { node ->
                val reports = CoCacheShadowReports.create(node.factory, keyPrefix, time.clock)
                reports.write(status("i0", time))
                reports.write(status("i1", time))
                putRaw(backend, keyPrefix, "i1", """{"format":9}""")
                reports.statusReadings().unreadable.assert().containsExactly("i1")
                // Nobody writes the list meanwhile, so nothing drops i1's entry from it: only its age ends the status.
                time.advance(Duration.ofHours(2))
                reports.statusReadings().unreadable.assert().describedAs("its entry current").containsExactly("i1")
                time.advance(ShadowCacheSpecs.INDEX_EXPIRY.minusHours(2).plusSeconds(1))
                reports.statusReadings().unreadable.assert().describedAs("its entry past the list's expiry").isEmpty()
            }
        }

    /** The line about an unreadable status is written at most once a minute per instance; the meter counts each read. */
    @Test
    fun `an unreadable status is logged at most once a minute per instance`() = runBlocking<Unit> {
        val lines = CopyOnWriteArrayList<String>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if ("does not read" in event.formattedMessage) lines += event.formattedMessage
            }
        }.also { it.start() }
        val logger = LoggerFactory.getLogger(CoCacheShadowReports::class.java) as Logger
        logger.addAppender(appender)
        try {
            val time = Time()
            val backend = InMemoryBackend(time)
            val keyPrefix = newKeyPrefix()
            SnapshotNode(backend, keyPrefix).use { node ->
                val reports = CoCacheShadowReports.create(node.factory, keyPrefix, time.clock)
                listOf("i0", "i1", "i2").forEach { reports.write(status(it, time)) }
                putRaw(backend, keyPrefix, "i1", """{"format":9}""")
                putRaw(backend, keyPrefix, "i2", "{not a status")
                repeat(3) {
                    reports.statusReadings()
                    time.advance(Duration.ofSeconds(5))
                }
                lines.size.assert().describedAs("one line per instance in the first minute").isEqualTo(2)
                lines.any { "[i1]" in it }.assert().isTrue()
                lines.any { "[i2]" in it }.assert().isTrue()
                time.advance(Duration.ofSeconds(50))
                reports.statusReadings()
                lines.size.assert().describedAs("one more each once a minute has passed").isEqualTo(4)
            }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `a status under another instance's key fails the read`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { node ->
            val reports = CoCacheShadowReports.create(node.factory, keyPrefix, Clock.systemUTC())
            listOf("i0", "i1").forEach { reports.write(status(it)) }
            SnapshotNode(backend, keyPrefix).use { other ->
                other.runtime.create(ShadowCacheSpecs.statuses(keyPrefix)).async().put("i1", status("i1", of = "i0"))
                    .await()
            }
            runCatching { reports.statusReadings() }.exceptionOrNull().assert().isInstanceOf(
                IllegalStateException::class.java
            )
        }
    }

    @Test
    fun `a store that does not answer fails the whole read, never reads as an unreadable status`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val statuses = node.runtime.create(ShadowCacheSpecs.statuses(node.keyPrefix))
            val down = object : Store<String, InstanceStatus> by statuses {
                override fun async(): AsyncStore<String, InstanceStatus> =
                    object : AsyncStore<String, InstanceStatus> by statuses.async() {
                        override fun getIfChanged(
                            key: String,
                            known: Digest?,
                        ): CompletableFuture<ConditionalGet<InstanceStatus>> = CompletableFuture.failedFuture(
                            CacheReadException(name, "getIfChanged", IllegalStateException("timeout"))
                        )
                    }
            }
            val reports = CoCacheShadowReports(
                node.runtime.create(ShadowCacheSpecs.reports(node.keyPrefix)),
                node.runtime.create(ShadowCacheSpecs.reporters(node.keyPrefix)),
                down,
                node.runtime.create(ShadowCacheSpecs.instances(node.keyPrefix)),
            )
            reports.write(status("i0"))
            val failed = runCatching { reports.statusReadings() }.exceptionOrNull()
            failed.assert().isNotNull()
            generateSequence(failed) { it.cause }.none { it is SnapshotFormatException }.assert().isTrue()
        }
    }
}
