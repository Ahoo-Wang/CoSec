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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `MIG-4`: `repair-references` converts a back-reference value of the format before 8.0.0-beta.5 into a set, and never
 * replaces a set: whatever a set holds, a member a publish added included, stays. While a value is in the old format no
 * publish can add to it (`CTL-42`), so no member is lost between the conversion's read and its write.
 */
class LegacyConversionTest {
    private val backend = InMemoryBackend()
    private val keyPrefix = "migrate-test:${UUID.randomUUID()}"
    private val shared = PolicyId("shared")
    private val acme = SnapshotKey.Tenant("acme")
    private val beta = SnapshotKey.Tenant("beta")

    private fun legacy(value: Map<SnapshotKey, Long>) {
        LegacyStore(backend, keyPrefix).use { it.references(SnapshotKey.Platform, shared, value) }
    }

    private fun <T> converting(block: suspend (CoCacheControlStore, LegacyConversion) -> T): T =
        CacheRuntime.builder().backend(backend).build().use { runtime ->
            CacheRuntime.builder().backend(backend).build().use { legacyRuntime ->
                val factory = CoCacheFactory(runtime)
                val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
                val store = CoCacheControlStore.create(factory, heads, keyPrefix)
                val conversion = LegacyConversion.create(store, factory, CoCacheFactory(legacyRuntime), keyPrefix)
                runBlocking { block(store, conversion) }
            }
        }

    @Test
    fun `an old-format value becomes a set of entries that refer, each at least at revision 1`() {
        legacy(mapOf(acme to 3L, beta to 0L))
        converting { store, conversion ->
            conversion.isLegacy(SnapshotKey.Platform, shared).assert().isTrue()
            conversion.convert(SnapshotKey.Platform, shared).assert().isTrue()
            store.referrers(SnapshotKey.Platform, shared).assert()
                .containsExactlyInAnyOrder(ReferrerEntry(acme, 3), ReferrerEntry(beta, 1))
            conversion.isLegacy(SnapshotKey.Platform, shared).assert().isFalse()
            // Absent is no old format either: nothing to convert.
            conversion.convert(SnapshotKey.Platform, PolicyId("other")).assert().isFalse()
        }
    }

    /** `CTL-43`: the old format did not say which referrer defines the id itself; the conversion reads it off its head. */
    @Test
    fun `a converted entry says it defines the id when its referrer's head does`() {
        legacy(mapOf(acme to 2L, beta to 1L))
        converting { store, conversion ->
            conversion.convert(SnapshotKey.Platform, shared) { it == acme }.assert().isTrue()
            store.referrers(SnapshotKey.Platform, shared).assert()
                .containsExactlyInAnyOrder(ReferrerEntry(acme, 2, definesItself = true), ReferrerEntry(beta, 1))
        }
    }

    @Test
    fun `a set is never replaced, so a member added after the conversion stays`() {
        legacy(mapOf(acme to 1L))
        converting { store, conversion ->
            // Before the conversion no publish can add a member: the old format refuses it.
            runCatching { store.addReferrer(SnapshotKey.Platform, shared, ReferrerEntry(beta, 2)) }
                .exceptionOrNull()?.message.assert().contains("cosec-migrate repair-references")
            conversion.convert(SnapshotKey.Platform, shared).assert().isTrue()
            store.addReferrer(SnapshotKey.Platform, shared, ReferrerEntry(beta, 2))
            // A second run, or a run that read the value before: the set stays as it is.
            conversion.convert(SnapshotKey.Platform, shared).assert().isFalse()
            store.referrers(SnapshotKey.Platform, shared).assert()
                .containsExactlyInAnyOrder(ReferrerEntry(acme, 1), ReferrerEntry(beta, 2))
        }
    }

    @Test
    fun `the old document list is read until the repair removes it`() {
        converting { store, _ -> store.legacyDocuments().assert().isNull() }
        LegacyStore(backend, keyPrefix).use { it.documents("tenant:acme") }
        converting { store, _ ->
            store.legacyDocuments().assert().containsExactly("tenant:acme")
            store.listDocuments(store.legacyDocuments()!!)
            store.removeLegacyDocuments()
            store.legacyDocuments().assert().isNull()
            store.documents().assert().containsExactly(acme)
        }
    }

    /** The old format's bytes, as 8.0.0-beta.4 wrote them, and what it refuses. */
    @Test
    fun `the old format reads what beta 4 wrote and refuses anything else`() {
        val referrers = mapOf(SnapshotKey.Tenant("b") to 7L, SnapshotKey.Platform to 0L, SnapshotKey.App("order") to 2L)
        val written = """{"app:order":2,"platform":0,"tenant:b":7}"""
        LegacyReferences.Format.encode(referrers).decodeToString().assert().isEqualTo(written)
        LegacyReferences.Format.decode(written.encodeToByteArray()).assert().isEqualTo(referrers)
        listOf("1", """["tenant:b"]""", """{"policies":1}""", """{"tenant:b":-1}""", """{"tenant:b":"1"}""", "{")
            .forEach { text ->
                assertThrownBy<SnapshotFormatException> { LegacyReferences.Format.decode(text.encodeToByteArray()) }
            }
    }
}
