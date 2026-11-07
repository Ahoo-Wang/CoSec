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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** RC1-41: the control plane's cache of stored documents is bounded, by entries and by stored size. */
class DocumentCacheTest {
    private val document = StoreFixtures.candidate(SnapshotKey.Tenant("acme"))

    private fun key(index: Int) = SnapshotKey.Tenant("t$index")

    private fun digest(seed: Int) = SnapshotDigest(ByteArray(20) { seed.toByte() })

    @Test
    fun `the least recently used document leaves first`() {
        val cache = DocumentCache(maxEntries = 2, maxBytes = 1_000)
        cache.put(key(1), digest(1), 1, document)
        cache.put(key(2), digest(2), 1, document)
        // Reading the first makes the second the least recently used.
        cache.get(key(1), digest(1)).assert().isNotNull()
        cache.put(key(3), digest(3), 1, document)
        cache.size.assert().isEqualTo(2)
        cache.get(key(2), digest(2)).assert().isNull()
        cache.get(key(1), digest(1)).assert().isNotNull()
        // The same key at another digest is another document.
        cache.get(key(1), digest(9)).assert().isNull()
    }

    @Test
    fun `the stored size is bounded, but the newest document stays`() {
        val cache = DocumentCache(maxEntries = 10, maxBytes = 100)
        cache.put(key(1), digest(1), 60, document)
        cache.put(key(2), digest(2), 60, document)
        cache.size.assert().isEqualTo(1)
        cache.get(key(2), digest(2)).assert().isNotNull()
        cache.put(key(3), digest(3), 500, document)
        cache.size.assert().isEqualTo(1)
        cache.get(key(3), digest(3)).assert().isNotNull()
    }

    @Test
    fun `the first entry put is the one every caller gets`() {
        val cache = DocumentCache()
        val first = cache.put(key(1), digest(1), 1, document)
        cache.put(key(1), digest(1), 1, StoreFixtures.candidate(SnapshotKey.Tenant("acme"))).assert().isSameAs(first)
    }

    @Test
    fun `concurrent first calls compile once, and keep two platforms' forms`() {
        val entry = DocumentCache().put(key(1), digest(1), 1, document)
        val compilations = AtomicInteger()
        val start = CountDownLatch(1)
        val compiled = CompiledDocument.tenant(TenantId("t1"), emptyList(), emptyList())
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val results = (0 until THREADS).map {
                pool.submit<Result<CompiledDocument>> {
                    start.await()
                    entry.compiled(digest(7)) {
                        compilations.incrementAndGet()
                        Thread.sleep(20)
                        compiled
                    }
                }
            }
            start.countDown()
            results.map { it.get(10, TimeUnit.SECONDS).getOrThrow() }.forEach { it.assert().isSameAs(compiled) }
        } finally {
            pool.shutdownNow()
        }
        compilations.get().assert().isEqualTo(1)
        // A second and a third platform: the oldest form leaves, so the first platform's compiles again.
        entry.compiled(digest(8)) { compilations.incrementAndGet().let { compiled } }
        entry.compiled(digest(9)) { compilations.incrementAndGet().let { compiled } }
        entry.compiled(digest(9)) { compilations.incrementAndGet().let { compiled } }
        compilations.get().assert().isEqualTo(3)
        entry.compiled(digest(7)) { compilations.incrementAndGet().let { compiled } }
        compilations.get().assert().isEqualTo(4)
    }

    private companion object {
        const val THREADS = 8
    }
}
