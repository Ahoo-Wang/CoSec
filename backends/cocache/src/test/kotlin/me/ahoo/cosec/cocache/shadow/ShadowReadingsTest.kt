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

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncStore
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
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * `CTL-56`: a report that is listed and cannot be read (missing, not decoding, or another's) is that instance's reading,
 * named unreadable, while the others are read; a store that does not answer fails the whole call, never reads as an
 * unreadable report.
 */
class ShadowReadingsTest {
    private val now = Instant.parse("2026-11-20T00:00:00Z")

    private fun report(instance: String, narrowed: Long, of: String = instance) = ShadowReport(
        window = WINDOW,
        instance = of,
        counts = mapOf(ShadowOutcome.SAME to 3L, ShadowOutcome.NARROWED to narrowed),
        samples = emptyMap(),
        baseRevisions = setOf(4L),
        first = now,
        last = now,
    )

    @Test
    fun `a listed report that is missing, does not decode, or is another's is that instance's reading`() =
        runBlocking<Unit> {
            val backend = InMemoryBackend()
            val keyPrefix = newKeyPrefix()
            SnapshotNode(backend, keyPrefix).use { node ->
                SnapshotNode(backend, keyPrefix).use { other ->
                    val sink = CoCacheShadowReports.create(node.factory, keyPrefix, Clock.systemUTC())
                    listOf("i0", "i1", "i2", "i3").forEach { sink.write(report(it, 1)) }
                    sink.readings(WINDOW).unreadable.assert().isEmpty()
                    val reports = other.runtime.create(ShadowCacheSpecs.reports(keyPrefix))
                    reports.async().delete("$WINDOW:i1").await()
                    reports.async().put("$WINDOW:i3", report("i3", 1, of = "i0")).await()
                    SnapshotNode(backend, keyPrefix).use { raw ->
                        raw.runtime.create(
                            rawStore(
                                "RawReport",
                                keyPrefix,
                                CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME,
                                KeyEncoder<String> { it }
                            )
                        ).async().put("$WINDOW:i2", "{not a report".encodeToByteArray()).await()
                    }
                    val readings = sink.readings(WINDOW)
                    readings.reports.map { it.instance }.assert().containsExactly("i0")
                    readings.unreadable.assert().containsExactlyInAnyOrder("i1", "i2", "i3")
                    // The call that reads every report still fails on any of them, as before.
                    runCatching { sink.reports(WINDOW) }.isFailure.assert().isTrue()
                }
            }
        }

    @Test
    fun `a store that does not answer fails the whole call, never reads as an unreadable report`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val reports = node.runtime.create(ShadowCacheSpecs.reports(node.keyPrefix))
            // The window's reporters read; each report read fails as a store that does not answer does.
            val down = object : Store<String, ShadowReport> by reports {
                override fun async(): AsyncStore<String, ShadowReport> = object : AsyncStore<String, ShadowReport> by reports.async() {
                    override fun getIfChanged(key: String, known: Digest?): CompletableFuture<ConditionalGet<ShadowReport>> =
                        CompletableFuture.failedFuture(CacheReadException(name, "getIfChanged", IllegalStateException("timeout")))
                }
            }
            val sink = CoCacheShadowReports(
                down,
                node.runtime.create(ShadowCacheSpecs.reporters(node.keyPrefix)),
                node.runtime.create(ShadowCacheSpecs.statuses(node.keyPrefix)),
                node.runtime.create(ShadowCacheSpecs.instances(node.keyPrefix)),
            )
            sink.write(report("i0", 1))
            val failed = runCatching { sink.readings(WINDOW) }.exceptionOrNull()
            failed.assert().isNotNull()
            generateSequence(failed) { it.cause }.none { it is SnapshotFormatException }.assert().isTrue()
        }
    }

    private companion object {
        const val WINDOW = "c1@1"
    }
}
