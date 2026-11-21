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
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CompletableFuture

/**
 * `CTL-72`: a listed status that does not decode names its instance unreadable while every other status is read; a
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
                    rawStore("RawStatus", keyPrefix, CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME, KeyEncoder<String> { it })
                ).async().put("i1", """{"format":9,"at":"x"}""".encodeToByteArray()).await()
            }
            val readings = reports.statusReadings()
            readings.statuses.map { it.instance }.assert().containsExactlyInAnyOrder("i0", "i2")
            readings.unreadable.assert().containsExactly("i1")
            // The call that reads every status still fails on it, as before.
            runCatching { reports.statuses() }.exceptionOrNull().assert().isInstanceOf(SnapshotFormatException::class.java)
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
            runCatching { reports.statusReadings() }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
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
