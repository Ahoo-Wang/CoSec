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

package me.ahoo.cosec.runtime.shadow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The candidate source shares one store read among requests that miss on the same key at once: shadow sampling reads a
 * candidate per document a sampled request read, so a busy tenant's requests would otherwise each read the same key.
 */
class CandidateCoalescingTest {
    private val acme: SnapshotKey = SnapshotKey.Tenant("acme")
    private val document = SnapshotDocuments.read(
        """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray()
    )
    private val compiled = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList())

    @Test
    fun `requests that miss on one key at once share one read`() = runBlocking<Unit> {
        val reads = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val store = CandidateStore {
            reads.incrementAndGet()
            release.await()
            StoredCandidate("w", 4, document)
        }
        val source = CachingCandidateSource(store, { _, _ -> compiled })
        val asked = List(WAITERS) { async { source.candidate(acme) } }
        // Every request has started and waits on the one read before the store answers.
        repeat(WAITERS) { yield() }
        reads.get().assert().isEqualTo(1)
        release.complete(Unit)
        asked.awaitAll().forEach { (it as Candidate.Ready).window.assert().isEqualTo("w") }
        reads.get().assert().isEqualTo(1)
        // Answered from the cache now; a later miss reads again.
        source.candidate(acme)
        reads.get().assert().isEqualTo(1)
    }

    @Test
    fun `a failed shared read answers every request no candidate, and the next miss reads again`() = runBlocking<Unit> {
        val reads = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val store = CandidateStore {
            if (reads.incrementAndGet() == 1) {
                release.await()
                error("Redis is down")
            }
            null
        }
        val source = CachingCandidateSource(store, { _, _ -> compiled })
        val asked = List(WAITERS) { async { source.candidate(acme) } }
        repeat(WAITERS) { yield() }
        release.complete(Unit)
        asked.awaitAll().forEach { it.assert().isNull() }
        source.failures.assert().isEqualTo(1)
        source.candidate(acme).assert().isNull()
        reads.get().assert().isEqualTo(2)
    }

    private companion object {
        const val WAITERS = 8
    }
}
