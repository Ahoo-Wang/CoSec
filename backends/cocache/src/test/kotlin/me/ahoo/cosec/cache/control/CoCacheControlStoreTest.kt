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

package me.ahoo.cosec.cache.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.RedisTarget
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.cache.snapshot.newKeyPrefix
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.PendingRecord
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** [CoCacheControlStore]'s conditional writes on real CoCache engines; Redis for what must reach Redis. */
class CoCacheControlStoreTest {
    private val key = SnapshotKey.Tenant("acme")

    private fun document(revision: Long) = SnapshotDocument(
        2,
        revision,
        Instant.parse("2026-11-01T00:00:00Z"),
        """{"schema":2,"revision":$revision,"publishedAt":"2026-11-01T00:00:00Z"}""".encodeToByteArray(),
    )

    private fun store(
        node: SnapshotNode,
        heads: Cache<SnapshotKey, SnapshotDocument> = node.cache,
        lists: Cache<String, Map<String, Instant>> = node.runtime.create(ControlCacheSpecs.list(node.keyPrefix)),
        clock: Clock = Clock.systemUTC(),
        changes: Cache<ChangeId, ChangeRequest> = node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
    ) = CoCacheControlStore(
        heads = heads,
        records = node.runtime.create(ControlCacheSpecs.revisionRecord(node.keyPrefix)),
        indexes = node.runtime.create(ControlCacheSpecs.revisionIndex(node.keyPrefix)),
        references = node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
        leases = node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)),
        changes = changes,
        stages = node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
        candidates = node.runtime.create(me.ahoo.cosec.cache.shadow.ShadowCacheSpecs.candidate(node.keyPrefix)),
        lists = lists,
        clock = clock,
        missingGrace = Duration.ofHours(1),
    )

    private fun change(id: String, state: ChangeState) = ChangeRequest(
        id = ChangeId(id),
        key = key,
        base = Base(0, null),
        operations = emptyList(),
        author = Actor(TenantId("acme"), PrincipalId("alice")),
        note = "",
        kind = ProvenanceKind.PUBLISH,
        state = state,
        findings = emptyList(),
        candidate = null,
        checkedWith = emptyMap(),
        createdAt = Instant.parse("2026-11-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-11-01T00:00:00Z"),
    )

    @Test
    fun `indexed documents and open changes are listed, terminal ones are not`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val lists = node.runtime.create(ControlCacheSpecs.list(node.keyPrefix))
            val store = store(node, lists = lists)
            store.documents().assert().isEmpty()
            store.putIndex(key, null, RevisionIndex()).assert().isTrue()
            store.documents().assert().containsExactly(key)

            val open = change("c1", ChangeState.DRAFT)
            store.putChange(null, open).assert().isTrue()
            store.putChange(null, change("c2", ChangeState.DRAFT)).assert().isTrue()
            store.openChanges().assert().containsExactlyInAnyOrder(ChangeId("c1"), ChangeId("c2"))
            val digest = store.change(ChangeId("c1"))!!.digest
            store.putChange(digest, open.copy(state = ChangeState.ABANDONED)).assert().isTrue()
            store.openChanges().assert().containsExactly(ChangeId("c2"))

            // A removal lost after the terminal write: the id is dropped the next time the list is read.
            val listed = lists.get("open-changes")!!
            lists.put("open-changes", listed + ("c1" to Instant.EPOCH))
            store.openChanges().assert().containsExactly(ChangeId("c2"))
            lists.get("open-changes")!!.keys.assert().containsExactly("c2")
        }
    }

    /**
     * RC1-4: the grant analysis lists the documents on every tenant change. Each call asks Redis whether the list is the
     * one decoded last; only then is that set the answer.
     */
    @Test
    fun `the document list is asked for on every call and decoded again only when it changed`() = runBlocking<Unit> {
        val backend = HookedBackend(InMemoryBackend())
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { reading ->
            SnapshotNode(backend, keyPrefix).use { writing ->
                val store = store(reading)
                val other = store(writing)
                other.putIndex(key, null, RevisionIndex()).assert().isTrue()
                val first = store.documents()
                first.assert().containsExactly(key)
                // Unchanged in Redis: the set decoded before is the answer.
                store.documents().assert().isSameAs(first)
                // Listed by another instance: the next call sees it.
                val beta = SnapshotKey.Tenant("beta")
                other.putIndex(beta, null, RevisionIndex()).assert().isTrue()
                store.documents().assert().containsExactlyInAnyOrder(key, beta)
                // A read that fails throws, never answering from the set kept.
                backend.failAll = true
                assertThrownBy<Exception> { runBlocking { store.documents() } }
                backend.failAll = false
                store.documents().assert().containsExactlyInAnyOrder(key, beta)
            }
        }
    }

    /** Coordinator review: a change listed but not written yet is being written, or its write failed. */
    @Test
    fun `a listed change that is missing stays until the grace has passed`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        val keyPrefix = newKeyPrefix()
        // Two instances over one Redis: one whose first write of c1 fails after listing it, one that reads the list.
        SnapshotNode(backend, keyPrefix).use { writing ->
            SnapshotNode(backend, keyPrefix).use { reading ->
                val clock = MutableClock(Instant.parse("2026-11-01T00:00:00Z"))
                val changes = writing.runtime.create(ControlCacheSpecs.changeRequest(writing.keyPrefix))
                val failing = object : Cache<ChangeId, ChangeRequest> by changes {
                    override fun async(): AsyncCache<ChangeId, ChangeRequest> =
                        object : AsyncCache<ChangeId, ChangeRequest> by changes.async() {
                            override fun putIfMatch(
                                key: ChangeId,
                                expected: Digest?,
                                value: ChangeRequest
                            ): CompletableFuture<ConditionalPut> =
                                CompletableFuture.failedFuture(IllegalStateException("Redis is down"))
                        }
                }
                val writer = store(writing, clock = clock, changes = failing)
                assertThrownBy<IllegalStateException> {
                    runBlocking { writer.putChange(null, change("c1", ChangeState.DRAFT)) }
                }
                val lists = reading.runtime.create(ControlCacheSpecs.list(reading.keyPrefix))
                val reader = store(reading, lists = lists, clock = clock)
                // c1 is listed and missing: it may be being written, so it is not dropped yet.
                reader.openChanges().assert().containsExactly(ChangeId("c1"))
                clock.now = clock.now.plus(Duration.ofMinutes(59))
                reader.openChanges().assert().containsExactly(ChangeId("c1"))
                clock.now = clock.now.plus(Duration.ofMinutes(2))
                reader.openChanges().assert().isEmpty()
                lists.get("open-changes")!!.assert().isEmpty()
            }
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    @Test
    fun `heads and records share the data plane's digest`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val store = store(node)
            val first = document(1)
            val digest = store.putRecord(key, first)
            digest.assert().isEqualTo(RedisSnapshotDocumentStore.digestOf(first))
            store.putRecord(key, first).assert().isEqualTo(digest)
            (store.putHead(key, null, first) as HeadWrite.Written).digest.assert().isEqualTo(digest)
            store.head(key)!!.digest.assert().isEqualTo(digest)
            store.record(key, digest).assert().isEqualTo(first)
            val conflict = store.putHead(key, null, document(2)) as HeadWrite.Conflict
            conflict.current.assert().isEqualTo(digest)
            (
                store.putHead(
                    key,
                    digest,
                    document(2)
                ) as HeadWrite.Written
                ).digest.assert().isEqualTo(store.digestOf(document(2)))
        }
    }

    @Test
    fun `a head write whose reply was lost is read back`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val lostReply = object : Cache<SnapshotKey, SnapshotDocument> by node.cache {
                override fun async(): AsyncCache<SnapshotKey, SnapshotDocument> = object : AsyncCache<SnapshotKey, SnapshotDocument> by node.cache.async() {
                    override fun putIfMatch(
                        key: SnapshotKey,
                        expected: Digest?,
                        value: SnapshotDocument
                    ): CompletableFuture<ConditionalPut> =
                        node.cache.async().putIfMatch(key, expected, value).thenCompose {
                            CompletableFuture.failedFuture(
                                CacheWriteException(name, "putIfMatch", IllegalStateException("timeout"))
                            )
                        }
                }
            }
            val published = ArrayList<SnapshotKey>()
            val store = CoCacheControlStore(
                lostReply,
                node.runtime.create(ControlCacheSpecs.revisionRecord(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.revisionIndex(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
                node.runtime.create(me.ahoo.cosec.cache.shadow.ShadowCacheSpecs.candidate(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.list(node.keyPrefix)),
                { published += it },
            )
            store.putHead(key, null, document(1)).assert().isInstanceOf(HeadWrite.Written::class.java)
            published.assert().containsExactly(key)
            // A lost reply of a write that did not land because another one did is a conflict with that one.
            node.cache.put(key, document(2))
            (store.putHead(key, store.digestOf(document(1)), document(3)) as HeadWrite.Conflict).current
                .assert().isEqualTo(store.digestOf(document(2)))
        }
    }

    @Test
    fun `a failed write throws, and nothing is written`() = runBlocking<Unit> {
        val hooked = HookedBackend(InMemoryBackend())
        SnapshotNode(hooked, newKeyPrefix()).use { node ->
            val store = store(node)
            hooked.failWrites = true
            assertThrownBy<CacheWriteException> { runBlocking { store.putRecord(key, document(1)) } }
            assertThrownBy<CacheWriteException> { runBlocking { store.putHead(key, null, document(1)) } }
            hooked.failWrites = false
            store.record(key, store.digestOf(document(1))).assert().isNull()
            store.head(key).assert().isNull()
        }
    }

    @Test
    fun `indexes, references and leases are conditional`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val store = store(node)
            val index = RevisionIndex(listOf(RevisionEntry(1, store.digestOf(document(1)), Instant.EPOCH)))
            store.putIndex(key, null, index).assert().isTrue()
            store.putIndex(key, null, RevisionIndex.EMPTY).assert().isFalse()
            val stored = store.index(key)!!
            stored.value.assert().isEqualTo(index)
            store.putIndex(key, stored.digest, RevisionIndex.EMPTY).assert().isTrue()
            store.putIndex(key, stored.digest, index).assert().isFalse()

            val shared = PolicyId("cosec:shared")
            store.putReferrers(SnapshotKey.Platform, shared, null, setOf(key)).assert().isTrue()
            val referrers = store.referrers(SnapshotKey.Platform, shared)!!
            referrers.value.assert().containsExactly(key)
            store.putReferrers(SnapshotKey.Platform, shared, referrers.digest, emptySet()).assert().isTrue()
            store.referrers(SnapshotKey.Platform, shared)!!.value.assert().isEmpty()

            store.acquireLease("reclaim", "me", Duration.ofMinutes(1)).assert().isTrue()
            store.acquireLease("reclaim", "me", Duration.ofMinutes(1)).assert().isTrue()
            store.acquireLease("reclaim", "you", Duration.ofMinutes(1)).assert().isFalse()
        }
    }

    @Test
    fun `a removed record is gone from Redis`() = runBlocking<Unit> {
        val backend = RedisTarget.REDIS.backend()
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { node ->
            val store = store(node)
            val digest = store.putRecord(key, document(1))
            store.removeRecord(key, digest)
            CacheRuntime.builder().backend(backend).build().use { fresh ->
                fresh.create(ControlCacheSpecs.revisionRecord(keyPrefix))
                    .get(RevisionRecordKey(key, digest)).assert().isNull()
            }
        }
        backend.close()
    }

    @Test
    fun `the codecs read back what they write and reject anything else`() {
        val digest = SnapshotDigest(ByteArray(20) { it.toByte() })
        val index = RevisionIndex(
            listOf(RevisionEntry(3, digest, Instant.parse("2026-11-01T00:00:00Z"))),
            listOf(PendingRecord(digest, Instant.parse("2026-11-02T00:00:00Z"))),
            floor = 2,
        )
        RevisionIndexCodec.decode(RevisionIndexCodec.encode(index)).assert().isEqualTo(index)
        val keys = setOf(SnapshotKey.Tenant("b"), SnapshotKey.Platform, SnapshotKey.App("order"))
        SnapshotKeySetCodec.decode(SnapshotKeySetCodec.encode(keys)).assert().isEqualTo(keys)
        SnapshotKeySetCodec.encode(keys).decodeToString().assert().isEqualTo("""["app:order","platform","tenant:b"]""")
        listOf(
            "",
            "[]",
            """{"entries":[],"pending":[]}""",
            """{"entries":[],"pending":[],"floor":0}""",
            """{"entries":[{"revision":1,"digest":"zz","publishedAt":"2026-11-01T00:00:00Z"}],"pending":[],"floor":1}""",
            """{"entries":[],"pending":[{"digest":"${"0".repeat(40)}","since":"later"}],"floor":1}""",
            """{"entries":[],"pending":[],"floor":1,"x":1}""",
        ).forEach { text ->
            assertThrownBy<SnapshotFormatException> { RevisionIndexCodec.decode(text.encodeToByteArray()) }
        }
        listOf("{}", """["policies"]""", """["tenant:a:b"]""", "[1]").forEach { text ->
            assertThrownBy<SnapshotFormatException> { SnapshotKeySetCodec.decode(text.encodeToByteArray()) }
        }
        RevisionRecordKeyEncoder.encode(RevisionRecordKey(SnapshotKey.Tenant("acme"), digest))
            .assert().isEqualTo("tenant:acme:$digest")
        ReferenceKeyEncoder.encode(
            ReferenceKey(SnapshotKey.Platform, PolicyId("cosec:root"))
        ).assert().isEqualTo("platform:cosec:root")
    }
}
