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

package me.ahoo.cosec.cocache.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

/**
 * The control store's leases (`ControlStore.acquireLease`, `releaseLease`) on real CoCache engines with a clock the test
 * moves, so that a lease expires exactly when the test says (#1236): one holder at a time, a release never takes the
 * lease of whoever took it after, and a holder that takes its lease again keeps it for the full duration, as
 * [InMemoryControlStore] does.
 */
class ControlLeaseTest {
    private class Time : CacheClock {
        @Volatile
        var millis: Long = Instant.parse("2026-11-08T00:00:00Z").toEpochMilli()

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

    private val time = Time()

    private fun store(node: SnapshotNode, leases: Store<String, String>) = CoCacheControlStore(
        heads = node.cache,
        records = node.runtime.create(ControlCacheSpecs.revisionRecord(node.keyPrefix)),
        indexes = node.runtime.create(ControlCacheSpecs.revisionIndex(node.keyPrefix)),
        references = node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
        leases = leases,
        changes = node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
        stages = node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
        candidates = node.runtime.create(me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs.candidate(node.keyPrefix)),
        lists = node.runtime.create(ControlCacheSpecs.list(node.keyPrefix)),
        appLists = node.runtime.create(ControlCacheSpecs.appList(node.keyPrefix)),
        clock = time.clock,
    )

    /** [real], with [beforeRemoval] run right before each conditional removal is sent. */
    private fun intercepted(real: Store<String, String>, beforeRemoval: () -> Unit): Store<String, String> =
        object : Store<String, String> by real {
            override fun async(): AsyncStore<String, String> = object : AsyncStore<String, String> by real.async() {
                override fun deleteIfMatch(key: String, expected: Digest): CompletableFuture<ConditionalDelete> {
                    beforeRemoval()
                    return real.async().deleteIfMatch(key, expected)
                }
            }
        }

    /** What both stores must answer alike. */
    private suspend fun renewal(store: ControlStore) {
        store.acquireLease("shadow-windows", "a", Duration.ofSeconds(10)).assert().isTrue()
        time.advance(Duration.ofSeconds(9))
        store.acquireLease("shadow-windows", "a", Duration.ofSeconds(10)).assert().isTrue()
        time.advance(Duration.ofSeconds(2))
        // 11 s after the first acquisition, 2 s after the second: still a's.
        store.acquireLease("shadow-windows", "b", Duration.ofSeconds(10)).assert().isFalse()
        time.advance(Duration.ofSeconds(8))
        store.acquireLease("shadow-windows", "b", Duration.ofSeconds(10)).assert().isTrue()
        store.acquireLease("shadow-windows", "a", Duration.ofSeconds(10)).assert().isFalse()
    }

    @Test
    fun `a holder that takes its lease again keeps it for the full duration - in memory`() = runBlocking<Unit> {
        renewal(InMemoryControlStore(time.clock))
    }

    @Test
    fun `a holder that takes its lease again keeps it for the full duration`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(time), newKeyPrefix()).use { node ->
            renewal(store(node, node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix))))
        }
    }

    @Test
    fun `an expired lease is taken by another holder`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(time), newKeyPrefix()).use { node ->
            val store = store(node, node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)))
            store.acquireLease("shadow-windows", "a", Duration.ofSeconds(10)).assert().isTrue()
            store.acquireLease("shadow-windows", "b", Duration.ofSeconds(10)).assert().isFalse()
            time.advance(Duration.ofSeconds(11))
            store.acquireLease("shadow-windows", "b", Duration.ofSeconds(10)).assert().isTrue()
            store.acquireLease("shadow-windows", "a", Duration.ofSeconds(10)).assert().isFalse()
        }
    }

    /**
     * The timeline of #1236: the release read its holder, the lease expired and another instance took it, then the late
     * removal arrived. A third instance must still be refused.
     */
    @Test
    fun `a late release leaves the lease of the instance that took it after`() = runBlocking<Unit> {
        val backend = InMemoryBackend(time)
        val prefix = newKeyPrefix()
        // Two instances on one store of record.
        SnapshotNode(backend, prefix).use { first ->
            SnapshotNode(backend, prefix).use { second ->
                val successor = store(second, second.runtime.create(ControlCacheSpecs.lease(prefix)))
                val leases = intercepted(first.runtime.create(ControlCacheSpecs.lease(prefix))) {
                    time.advance(Duration.ofSeconds(11))
                    runBlocking {
                        successor.acquireLease("shadow-windows", "b", Duration.ofSeconds(60)).assert().isTrue()
                    }
                }
                val store = store(first, leases)
                store.acquireLease("shadow-windows", "a", Duration.ofSeconds(10)).assert().isTrue()
                store.releaseLease("shadow-windows", "a")
                store.acquireLease("shadow-windows", "c", Duration.ofSeconds(10)).assert().isFalse()
                store.acquireLease("shadow-windows", "b", Duration.ofSeconds(60)).assert().isTrue()
            }
        }
    }
}
