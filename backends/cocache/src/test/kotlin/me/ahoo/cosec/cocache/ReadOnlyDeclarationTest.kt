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

package me.ahoo.cosec.cocache

import me.ahoo.cocache.core.CacheLayers
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Layered
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.shadow.CoCacheCandidateStore
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `SNP-18`: whether the snapshot and candidate stores are read-only on an instance is CoSec's declaration, not a
 * layer's. A data-plane instance declares them read-only, a process that runs the control plane read-write, and a
 * `cocache.*` property or customizer (a [CacheLayers] here) that changes it, either way, fails the creation.
 */
class ReadOnlyDeclarationTest {
    /** A layer that sets read-only to [readOnly] on every store, as `cocache.caches.[name].read-only` would. */
    private class Flipping(private val readOnly: Boolean) : CacheLayers {
        override fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>, locks: Locks): Layered<StoreSpec<K, V>> =
            CacheLayers.NONE.apply(spec.toBuilder().apply { this.readOnly = this@Flipping.readOnly }.build(), locks)

        override fun <K : Any, V : Any> apply(spec: CacheSpec<K, V>, locks: Locks): Layered<CacheSpec<K, V>> =
            CacheLayers.NONE.apply(spec, locks)
    }

    private fun <T> factory(layers: CacheLayers = CacheLayers.NONE, block: (CoCacheFactory) -> T): T =
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { block(CoCacheFactory(it, layers)) }

    private fun refused(declared: Boolean, create: (CoCacheFactory) -> Unit) {
        val error = assertThrows<IllegalStateException> { factory(Flipping(!declared), create) }
        error.message.orEmpty().assert().contains("SNP-18").contains(if (declared) "read-write" else "read-only")
    }

    private fun snapshot(readOnly: Boolean): (CoCacheFactory) -> Unit = { factory ->
        factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(readOnly = readOnly))
    }

    private fun candidates(readOnly: Boolean): (CoCacheFactory) -> Unit = { factory ->
        CoCacheCandidateStore.create(factory, readOnly = readOnly)
    }

    @Test
    fun `a layer cannot make a data-plane instance's snapshot store read-write`() {
        refused(declared = true, snapshot(readOnly = true))
    }

    @Test
    fun `a layer cannot make the control plane's snapshot store read-only`() {
        refused(declared = false, snapshot(readOnly = false))
    }

    @Test
    fun `a layer cannot make a data-plane instance's candidate store read-write`() {
        refused(declared = true, candidates(readOnly = true))
    }

    @Test
    fun `a layer cannot make the control plane's candidate store read-only`() {
        refused(declared = false, candidates(readOnly = false))
    }

    @Test
    fun `a read-only snapshot store reads and refuses every write before it is sent`() {
        factory { factory ->
            val cache = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(readOnly = true))
            factory.runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)!!.readOnly.assert().isTrue()
            val document = SnapshotDocuments.read(
                """{"schema":2,"revision":1,"publishedAt":"2026-11-18T00:00:00Z","policies":[],"bindings":[]}"""
                    .encodeToByteArray()
            )
            assertThrows<UnsupportedOperationException> { cache.put(SnapshotKey.Platform, document) }
            cache[SnapshotKey.Platform].assert().isNull()
        }
    }
}
