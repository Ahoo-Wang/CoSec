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

package me.ahoo.cosec.cache.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.shadow.ShadowCacheSpecs
import me.ahoo.cosec.cache.snapshot.RedisTarget
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.cache.snapshot.newKeyPrefix
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * The control store removes a lease or a candidate only while it is still the one the caller read (`deleteIfMatch`,
 * CoCache 8), on real Redis. Each test makes another writer replace the value between that read and the removal, as a
 * lease that expires and is taken by another instance, or a slot another change takes, would: the replacement stays.
 */
class ConditionalRemovalTest {
    /** [real], with [beforeRemoval] run right before each removal is sent. */
    private fun <K : Any, V : Any> intercepted(real: Store<K, V>, beforeRemoval: () -> Unit): Store<K, V> =
        object : Store<K, V> by real {
            override fun async(): AsyncStore<K, V> = object : AsyncStore<K, V> by real.async() {
                override fun delete(key: K): CompletableFuture<Void?> {
                    beforeRemoval()
                    return real.async().delete(key)
                }

                override fun deleteIfMatch(key: K, expected: Digest): CompletableFuture<ConditionalDelete> {
                    beforeRemoval()
                    return real.async().deleteIfMatch(key, expected)
                }
            }
        }

    private fun unknown(): CacheWriteException =
        CacheWriteException("candidate", "deleteIfMatch", IllegalStateException("timed out"), true)

    /** The candidate store of one control store, its `deleteIfMatch` answered by [Candidates.answer] when set. */
    private class Candidates(val real: Store<SnapshotKey, StoredCandidate>) {
        var answer: ((SnapshotKey, Digest) -> CompletableFuture<ConditionalDelete>)? = null
        val answering: Store<SnapshotKey, StoredCandidate> = object : Store<SnapshotKey, StoredCandidate> by real {
            override fun async(): AsyncStore<SnapshotKey, StoredCandidate> =
                object : AsyncStore<SnapshotKey, StoredCandidate> by real.async() {
                    override fun deleteIfMatch(
                        key: SnapshotKey,
                        expected: Digest
                    ): CompletableFuture<ConditionalDelete> =
                        answer?.invoke(key, expected) ?: real.async().deleteIfMatch(key, expected)
                }
        }
    }

    private fun withCandidates(test: suspend (Candidates, CoCacheControlStore) -> Unit) {
        val backend = RedisTarget.REDIS.backend()
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val candidates = Candidates(node.runtime.create(ShadowCacheSpecs.candidate(node.keyPrefix)))
            val store = store(node, candidates = candidates.answering)
            runBlocking { test(candidates, store) }
        }
        backend.close()
    }

    private fun store(
        node: SnapshotNode,
        leases: Store<String, String> = node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)),
        candidates: Store<SnapshotKey, StoredCandidate> = node.runtime.create(
            ShadowCacheSpecs.candidate(node.keyPrefix)
        ),
    ) = CoCacheControlStore(
        heads = node.cache,
        records = node.runtime.create(ControlCacheSpecs.revisionRecord(node.keyPrefix)),
        indexes = node.runtime.create(ControlCacheSpecs.revisionIndex(node.keyPrefix)),
        references = node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
        leases = leases,
        changes = node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
        stages = node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
        candidates = candidates,
        lists = node.runtime.create(ControlCacheSpecs.list(node.keyPrefix)),
        appLists = node.runtime.create(ControlCacheSpecs.appList(node.keyPrefix)),
    )

    @Test
    fun `a lease another instance took after it was read is not released - lease race`() {
        val backend = RedisTarget.REDIS.backend()
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val real = node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix))
            // Between the release's read and its removal, the lease expires and another instance takes it.
            val leases = intercepted(real) { real.put("reclaim", "other", Duration.ofMinutes(1)) }
            val store = store(node, leases = leases)
            runBlocking {
                store.acquireLease("reclaim", "me", Duration.ofMinutes(1)).assert().isTrue()
                store.releaseLease("reclaim", "me")
                store.acquireLease("reclaim", "third", Duration.ofMinutes(1)).assert().isFalse()
                store.acquireLease("reclaim", "other", Duration.ofMinutes(1)).assert().isTrue()
            }
        }
        backend.close()
    }

    private val acme: SnapshotKey = SnapshotKey.Tenant("acme")
    private val document = SnapshotDocument(
        2,
        5,
        Instant.parse("2026-11-01T00:00:00Z"),
        """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray(),
    )

    @Test
    fun `a candidate another change staged after the withdrawal read it stays - #1227`() {
        val backend = RedisTarget.REDIS.backend()
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val real = node.runtime.create(ShadowCacheSpecs.candidate(node.keyPrefix))
            // Between the withdrawal's read and its removal, the change that took the slot stages its candidate.
            val candidates = intercepted(real) { real.put(acme, StoredCandidate("c2@2", 4, document)) }
            val store = store(node, candidates = candidates)
            runBlocking {
                store.putCandidate(acme, null, StoredCandidate("c1@1", 4, document)).assert().isTrue()
                store.withdrawCandidate(acme, "c1@1").assert().isFalse()
                store.candidate(acme)!!.window.assert().isEqualTo("c2@2")
            }
        }
        backend.close()
    }

    @Test
    fun `a candidate replaced by another of the same window is read again and withdrawn`() =
        withCandidates { candidates, store ->
            val real = candidates.real
            real.put(acme, StoredCandidate("c1@1", 4, document))
            candidates.answer = { key, expected ->
                candidates.answer = null
                real.put(key, StoredCandidate("c1@1", 5, document))
                real.async().deleteIfMatch(key, expected)
            }
            store.withdrawCandidate(acme, "c1@1").assert().isTrue()
            store.candidate(acme).assert().isNull()
        }

    @Test
    fun `a candidate that keeps changing is given up on`() = withCandidates { candidates, store ->
        val real = candidates.real
        real.put(acme, StoredCandidate("c1@1", 4, document))
        var revision = 4L
        candidates.answer = { key, expected ->
            real.put(key, StoredCandidate("c1@1", ++revision, document))
            real.async().deleteIfMatch(key, expected)
        }
        assertThrownBy<IllegalStateException> { runBlocking { store.withdrawCandidate(acme, "c1@1") } }
        store.candidate(acme)!!.window.assert().isEqualTo("c1@1")
    }

    @Test
    fun `a withdrawal whose outcome is unknown is read back`() = withCandidates { candidates, store ->
        val real = candidates.real
        // It removed it, and the reply was lost.
        real.put(acme, StoredCandidate("c1@1", 4, document))
        candidates.answer = { key, expected ->
            real.async().deleteIfMatch(key, expected).thenCompose { CompletableFuture.failedFuture(unknown()) }
        }
        store.withdrawCandidate(acme, "c1@1").assert().isTrue()
        store.candidate(acme).assert().isNull()

        // It did not apply: still the value read, so the failure is the answer.
        real.put(acme, StoredCandidate("c1@1", 4, document))
        candidates.answer = { _, _ -> CompletableFuture.failedFuture(unknown()) }
        assertThrownBy<CacheWriteException> { runBlocking { store.withdrawCandidate(acme, "c1@1") } }
        store.candidate(acme)!!.window.assert().isEqualTo("c1@1")

        // Another change staged meanwhile: not this caller's to remove.
        candidates.answer = { key, _ ->
            real.put(key, StoredCandidate("c2@2", 4, document))
            CompletableFuture.failedFuture(unknown())
        }
        store.withdrawCandidate(acme, "c1@1").assert().isFalse()
        store.candidate(acme)!!.window.assert().isEqualTo("c2@2")

        // A failure whose outcome is known is thrown as it is.
        candidates.answer = { _, _ ->
            CompletableFuture.failedFuture(
                CacheWriteException("candidate", "deleteIfMatch", IllegalStateException("refused"), false)
            )
        }
        assertThrownBy<CacheWriteException> { runBlocking { store.withdrawCandidate(acme, "c2@2") } }
        store.candidate(acme)!!.window.assert().isEqualTo("c2@2")
    }

    @Test
    fun `a lease still held is released, and one held by another is not`() {
        val backend = RedisTarget.REDIS.backend()
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val store = store(node)
            runBlocking {
                store.acquireLease("reclaim", "me", Duration.ofMinutes(1)).assert().isTrue()
                store.releaseLease("reclaim", "someone else")
                store.acquireLease("reclaim", "other", Duration.ofMinutes(1)).assert().isFalse()
                store.releaseLease("reclaim", "me")
                store.acquireLease("reclaim", "other", Duration.ofMinutes(1)).assert().isTrue()
                // Released when nobody holds it: nothing to do.
                store.releaseLease("absent", "me")
            }
        }
        backend.close()
    }
}
