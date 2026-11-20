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

package me.ahoo.cosec.cocache.control

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-46` on CoCache: the staged list is a set of record, one member per staged candidate, so stages of different
 * documents at once never conflict; it is absent (null) until the control plane keeps it, and empty, not null, when it is
 * kept and nothing is staged.
 */
class StagedListStoreTest {
    private fun candidate(window: String) = StoredCandidate(
        window,
        1,
        SnapshotDocuments.read(
            """{"schema":2,"revision":2,"publishedAt":"2026-11-20T00:00:00Z"}""".encodeToByteArray()
        ),
    )

    @Test
    fun `the list is absent until kept, then lists exactly what is added and not removed`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val store = CoCacheControlStore.create(node.factory, node.cache, node.keyPrefix)
            store.staged().assert().isNull()
            store.keepStagedList()
            store.staged().assert().isEqualTo(StagedCandidates.NONE)
            val acme = SnapshotKey.Tenant("acme")
            store.listStaged(acme, "c1@1")
            store.listStaged(acme, "c1@1")
            store.listStaged(SnapshotKey.Platform, "c2@2")
            store.listStaged(SnapshotKey.App("wms"), "c3@3")
            store.staged()!!.windows.assert().isEqualTo(
                mapOf(
                    acme to setOf("c1@1"),
                    SnapshotKey.Platform to setOf("c2@2"),
                    SnapshotKey.App("wms") to setOf("c3@3")
                )
            )
            store.unlistStaged(SnapshotKey.Platform, "c2@2")
            store.unlistStaged(acme, "c9@9")
            store.staged()!!.isStaged(SnapshotKey.Platform).assert().isFalse()
            store.staged()!!.isStaged(acme).assert().isTrue()
        }
    }

    @Test
    fun `fifty documents listed and staged at once all land`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val store = CoCacheControlStore.create(node.factory, node.cache, node.keyPrefix)
            store.keepStagedList()
            val start = CompletableDeferred<Unit>()
            val tenants = (1..STAGES).map { SnapshotKey.Tenant("tenant-$it") }
            tenants.map { tenant ->
                async(Dispatchers.Default) {
                    start.await()
                    // A stage's two steps: the member, then the candidate.
                    store.listStaged(tenant, "${tenant.tenantId}@1")
                    store.putCandidate(tenant, null, candidate("${tenant.tenantId}@1")).assert().isTrue()
                }
            }.also { start.complete(Unit) }.awaitAll()
            val listed = store.staged()!!.windows
            listed.keys.assert().containsExactlyInAnyOrderElementsOf(tenants)
            tenants.forEach { listed.getValue(it).assert().containsExactly("${it.tenantId}@1") }
        }
    }

    @Test
    fun `a member naming no document and the marker are not staged candidates`() {
        StagedMember.decode(StagedMember.KEPT).assert().isNull()
        StagedMember.decode("app:a:b/w").assert().isNull()
        StagedMember.decode("/w").assert().isNull()
        StagedMember.decode("tenant:acme/").assert().isNull()
        StagedMember.decode("tenant:acme/c1@1/x").assert().isEqualTo(SnapshotKey.Tenant("acme") to "c1@1/x")
        StagedMember.encode(SnapshotKey.Tenant("acme"), "c1@1").assert().isEqualTo("tenant:acme/c1@1")
    }

    private companion object {
        const val STAGES = 50
    }
}
