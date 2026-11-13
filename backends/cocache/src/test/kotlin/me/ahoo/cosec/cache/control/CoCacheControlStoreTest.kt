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
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.snapshot.RawCodec
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.RedisTarget
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.cache.snapshot.newKeyPrefix
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlMaintenance
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.PendingRecord
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.shadow.StoredCandidate
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
        heads: Store<SnapshotKey, SnapshotDocument> = node.cache,
        lists: Store<String, Map<String, Instant>> = node.runtime.create(ControlCacheSpecs.list(node.keyPrefix)),
        appLists: Store<String, AppList> = node.runtime.create(ControlCacheSpecs.appList(node.keyPrefix)),
        clock: Clock = Clock.systemUTC(),
        changes: Store<ChangeId, ChangeRequest> = node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
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
        appLists = appLists,
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

    /**
     * SCP-10: a back-reference set naming an app no key can name is a format error of the whole set, so a publish that
     * would write it fails, and the set stays as it was until an operator repairs it.
     */
    @Test
    fun `a back-reference set naming an app no key can name is a format error`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val store = store(node)
            val revisions = Revisions(store, Clock.systemUTC())
            val shared = PolicyId("shared")
            revisions.publish(SnapshotKey.Platform, null, document(DocumentKind.PLATFORM, "", SHARED), BOOTSTRAP)
            CacheRuntime.builder().backend(backend).build().use { raw ->
                raw.create(
                    rawStore(
                        "RawReferences",
                        node.keyPrefix,
                        CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME,
                        ReferenceKeyEncoder
                    )
                ).put(ReferenceKey(SnapshotKey.Platform, shared), """{"app:a\u0001b":1}""".encodeToByteArray())
            }
            formatError { store.referrers(SnapshotKey.Platform, shared) }.message.assert().doesNotContain("\u0001")
            formatError {
                revisions.publish(
                    SnapshotKey.Tenant("acme"),
                    null,
                    document(DocumentKind.TENANT, "acme", BINDS_SHARED),
                    BOOTSTRAP,
                )
            }
            revisions.head(SnapshotKey.Tenant("acme")).assert().isNull()
        }
    }

    /**
     * The upgrade note's path: `repair-references` reads a set before it writes it, so the array form 8.0.0-alpha.3 wrote
     * stops it until the set is deleted; it then writes the set again from the head, with the referrer's revision.
     */
    @Test
    fun `repair stops at a set in the array form, and rebuilds it with revisions once it is deleted`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        val keyPrefix = newKeyPrefix()
        val acme = SnapshotKey.Tenant("acme")
        val reference = ReferenceKey(SnapshotKey.Platform, PolicyId("shared"))
        SnapshotNode(backend, keyPrefix).use { node ->
            val revisions = Revisions(store(node), Clock.systemUTC())
            revisions.publish(SnapshotKey.Platform, null, document(DocumentKind.PLATFORM, "", SHARED), BOOTSTRAP)
            revisions.publish(acme, null, document(DocumentKind.TENANT, "acme", BINDS_SHARED), BOOTSTRAP)
        }
        CacheRuntime.builder().backend(backend).build().use { raw ->
            raw.create(
                rawStore("RawReferences", keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME, ReferenceKeyEncoder)
            )
                .put(reference, """["tenant:acme"]""".encodeToByteArray())
        }
        SnapshotNode(backend, keyPrefix).use { node ->
            formatError { Revisions(store(node), Clock.systemUTC()).repairReferences(acme) }
        }
        CacheRuntime.builder().backend(backend).build().use { raw ->
            raw.create(
                rawStore("RawReferences", keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME, ReferenceKeyEncoder)
            )
                .delete(reference)
        }
        SnapshotNode(backend, keyPrefix).use { node ->
            val store = store(node)
            Revisions(store, Clock.systemUTC()).repairReferences(acme).assert().isEqualTo(1)
            store.referrers(SnapshotKey.Platform, reference.policy)!!.value.assert().isEqualTo(mapOf(acme to 1L))
        }
    }

    /** The format error [block] fails with, however CoCache wraps what the codec threw. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun formatError(block: suspend () -> Unit): SnapshotFormatException {
        val error = try {
            block()
            null
        } catch (e: Exception) {
            e
        }
        return generateSequence<Throwable>(checkNotNull(error) { "It did not fail." }) { it.cause }
            .filterIsInstance<SnapshotFormatException>().first()
    }

    private fun document(kind: DocumentKind, owner: String, body: String) = PolicyDocumentCodec.decode(
        kind,
        owner,
        """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"$body}""".encodeToByteArray(),
    )

    /**
     * SCP-10: a stored open change of an app no document key can name does not decode, so it is not known to be
     * terminal: the listing fails, however CoCache wraps what the codec threw.
     */
    @Test
    fun `an open change of an app no key can name fails the listing`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val store = store(node)
            store.putChange(null, change("c1", ChangeState.DRAFT)).assert().isTrue()
            store.putChange(null, change("c2", ChangeState.DRAFT)).assert().isTrue()
            val written = ChangeRequestCodec.encode(change("c1", ChangeState.DRAFT)).decodeToString()
                .replace("\"key\":\"tenant:acme\"", "\"key\":\"app:a\\u0001b\"")
            CacheRuntime.builder().backend(backend).build().use { raw ->
                raw.create(
                    rawStore(
                        "RawChange",
                        node.keyPrefix,
                        CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME,
                        KeyEncoder<ChangeId> { it.value }
                    )
                ).put(ChangeId("c1"), written.encodeToByteArray())
            }
            formatError { store.openChanges() }
            formatError { store.change(ChangeId("c1")) }
        }
    }

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
            store.openChanges().map { it.id }.assert().containsExactlyInAnyOrder(ChangeId("c1"), ChangeId("c2"))
            val digest = store.change(ChangeId("c1"))!!.digest
            store.putChange(digest, open.copy(state = ChangeState.ABANDONED)).assert().isTrue()
            store.openChanges().map { it.id }.assert().containsExactly(ChangeId("c2"))

            // A removal lost after the terminal write: the id is dropped the next time the list is read.
            val listed = lists.get("open-changes")!!
            lists.put("open-changes", listed + ("c1" to Instant.EPOCH))
            store.openChanges().map { it.id }.assert().containsExactly(ChangeId("c2"))
            lists.get("open-changes")!!.keys.assert().containsExactly("c2")
        }
    }

    /**
     * #1219: a pass of the control plane's maintenance reads each open change once. Before, the store read each of them
     * to drop the terminal ones and the pass read them again, so every pass cost twice the open changes.
     */
    @Test
    fun `a maintenance pass reads each open change once - #1219`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val reads = java.util.concurrent.ConcurrentHashMap<ChangeId, Int>()
            val real = node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix))
            val counting = object : Store<ChangeId, ChangeRequest> by real {
                override fun async(): AsyncStore<ChangeId, ChangeRequest> =
                    object : AsyncStore<ChangeId, ChangeRequest> by real.async() {
                        override fun getIfChanged(key: ChangeId, digest: Digest?) =
                            real.async().getIfChanged(key, digest).also { reads.merge(key, 1, Int::plus) }
                    }
            }
            val clock = MutableClock(Instant.parse("2026-11-01T00:00:00Z"))
            val store = store(node, clock = clock, changes = counting)
            repeat(OPEN_CHANGES) { store.putChange(null, change("c$it", ChangeState.CHECKED)) }
            val revisions = Revisions(store, clock)
            val changes = Changes(
                store,
                revisions,
                { ChangeId("unused") },
                { _, _, _ -> ControlDecision.PERMIT },
                clock
            )
            val maintenance = ControlMaintenance(store, revisions, changes, Duration.ofMinutes(1))
            reads.clear()

            maintenance.closeShadowWindows().assert().isEmpty()

            reads.keys.assert().hasSize(OPEN_CHANGES)
            reads.values.assert().allMatch { it == 1 }
        }
    }

    /** #1235: a candidate is written over the candidate that was read, never over another one. */
    @Test
    fun `a candidate is written over the candidate read - #1235`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val store = store(node)
            val body = """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z"}""".encodeToByteArray()
            fun candidate(window: String) =
                StoredCandidate(window, 4, SnapshotDocument(2, 5, Instant.parse("2026-11-01T00:00:00Z"), body))
            store.stagedCandidate(key).assert().isNull()
            store.putCandidate(key, null, candidate("c1@1")).assert().isTrue()
            // Another stage that read nothing: its write finds the first one there.
            store.putCandidate(key, null, candidate("c2@2")).assert().isFalse()
            val staged = store.stagedCandidate(key)!!
            staged.value.window.assert().isEqualTo("c1@1")
            store.putCandidate(key, staged.digest, candidate("c2@2")).assert().isTrue()
            // A stage that read the first candidate and was slow: the digest it names is not the candidate's any more.
            store.putCandidate(key, staged.digest, candidate("c3@3")).assert().isFalse()
            store.candidate(key)!!.window.assert().isEqualTo("c2@2")
        }
    }

    /** RC1-4: the app list is one small conditional value, read from Redis on every call. */
    @Test
    fun `the app list is conditional, strict and read from Redis`() = runBlocking<Unit> {
        val backend = HookedBackend(InMemoryBackend())
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { reading ->
            SnapshotNode(backend, keyPrefix).use { writing ->
                val store = store(reading)
                val other = store(writing)
                store.apps().assert().isNull()
                other.putApps(null, AppList(setOf("order"), complete = false)).assert().isTrue()
                other.putApps(null, AppList(emptySet(), complete = true)).assert().isFalse()
                // Written by another instance: read at once.
                val listed = store.apps()!!
                listed.value.assert().isEqualTo(AppList(setOf("order"), complete = false))
                store.putApps(listed.digest, AppList(setOf("order", "billing"), complete = true)).assert().isTrue()
                store.putApps(listed.digest, AppList(emptySet(), complete = true)).assert().isFalse()
                other.apps()!!.value.assert().isEqualTo(AppList(setOf("billing", "order"), complete = true))
                backend.failAll = true
                assertThrownBy<Exception> { runBlocking { store.apps() } }
                backend.failAll = false
            }
        }
        // Entries are kept as written, whatever they are; anything but the two members is refused.
        val odd = AppList(setOf("not:an app", "order"), complete = true)
        val encoded = AppListCodec.encode(odd)
        AppListCodec.decode(encoded).assert().isEqualTo(odd)
        encoded.decodeToString().assert().isEqualTo("""{"apps":["not:an app","order"],"complete":true}""")
        for (refused in listOf(
            """{"apps":[],"complete":true,"x":1}""",
            """{"apps":[1],"complete":true}""",
            """{"apps":[],"complete":"yes"}""",
        )) {
            assertThrownBy<SnapshotFormatException> { AppListCodec.decode(refused.encodeToByteArray()) }
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
                val failing = object : Store<ChangeId, ChangeRequest> by changes {
                    override fun async(): AsyncStore<ChangeId, ChangeRequest> =
                        object : AsyncStore<ChangeId, ChangeRequest> by changes.async() {
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
                // c1 is listed and missing: it may be being written, so it is not dropped yet (and there is no change).
                reader.openChanges().assert().isEmpty()
                lists.get("open-changes")!!.keys.assert().containsExactly("c1")
                clock.now = clock.now.plus(Duration.ofMinutes(59))
                reader.openChanges().assert().isEmpty()
                lists.get("open-changes")!!.keys.assert().containsExactly("c1")
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
            val lostReply = object : Store<SnapshotKey, SnapshotDocument> by node.cache {
                override fun async(): AsyncStore<SnapshotKey, SnapshotDocument> = object : AsyncStore<SnapshotKey, SnapshotDocument> by node.cache.async() {
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
                node.runtime.create(ControlCacheSpecs.appList(node.keyPrefix)),
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

    /**
     * A write CoCache reports as not executed is not read back: the same bytes another writer stored in between are not
     * this write landing.
     */
    @Test
    fun `a head write that was not executed throws, though the same bytes are there`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val rejected = object : Store<SnapshotKey, SnapshotDocument> by node.cache {
                override fun async(): AsyncStore<SnapshotKey, SnapshotDocument> = object : AsyncStore<SnapshotKey, SnapshotDocument> by node.cache.async() {
                    override fun putIfMatch(
                        key: SnapshotKey,
                        expected: Digest?,
                        value: SnapshotDocument
                    ): CompletableFuture<ConditionalPut> = CompletableFuture.failedFuture(
                        CacheWriteException(
                            name,
                            "putIfMatch",
                            IllegalStateException("rejected"),
                            outcomeUnknown = false
                        )
                    )
                }
            }
            val store = CoCacheControlStore(
                rejected,
                node.runtime.create(ControlCacheSpecs.revisionRecord(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.revisionIndex(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
                node.runtime.create(me.ahoo.cosec.cache.shadow.ShadowCacheSpecs.candidate(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.list(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.appList(node.keyPrefix)),
                { },
            )
            node.cache.put(key, document(1))
            assertThrownBy<CacheWriteException> { runBlocking { store.putHead(key, null, document(1)) } }
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
            store.putReferrers(SnapshotKey.Platform, shared, null, mapOf(key to 3L)).assert().isTrue()
            val referrers = store.referrers(SnapshotKey.Platform, shared)!!
            referrers.value.assert().isEqualTo(mapOf(key to 3L))
            // Another revision is another value: a removal read before it no longer matches.
            store.putReferrers(SnapshotKey.Platform, shared, referrers.digest, mapOf(key to 4L)).assert().isTrue()
            store.putReferrers(SnapshotKey.Platform, shared, referrers.digest, emptyMap()).assert().isFalse()
            val raised = store.referrers(SnapshotKey.Platform, shared)!!
            store.putReferrers(SnapshotKey.Platform, shared, raised.digest, emptyMap()).assert().isTrue()
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

    /** #1207: each referrer with the revision of it that last indexed the reference. */
    @Test
    fun `the referrers codec reads back what it writes and rejects anything else`() {
        val referrers = mapOf(
            SnapshotKey.Tenant("b") to 1L,
            SnapshotKey.Platform to 0L,
            SnapshotKey.App("order") to 12L
        )
        ReferrersCodec.decode(ReferrersCodec.encode(referrers)).assert().isEqualTo(referrers)
        ReferrersCodec.encode(referrers).decodeToString().assert()
            .isEqualTo("""{"app:order":12,"platform":0,"tenant:b":1}""")
        listOf(
            "1",
            "\"tenant:b\"",
            // The array of keys 8.0.0-alpha.3 wrote.
            """["tenant:b"]""",
            """["policies"]""",
            """["tenant:a:b"]""",
            "[1]",
            // An app no key can name (SCP-10).
            """{"app:a\u0001b":1}""",
            """{"policies":1}""",
            """{"tenant:b":-1}""",
            """{"tenant:b":"1"}""",
            """{"tenant:b":1.5}""",
        ).forEach { text ->
            assertThrownBy<SnapshotFormatException> { ReferrersCodec.decode(text.encodeToByteArray()) }
        }
    }

    @Test
    fun `the codecs read back what they write and reject anything else`() {
        val digest = SnapshotDigest(ByteArray(32) { it.toByte() })
        val index = RevisionIndex(
            listOf(RevisionEntry(3, digest, Instant.parse("2026-11-01T00:00:00Z"))),
            listOf(PendingRecord(digest, Instant.parse("2026-11-02T00:00:00Z"))),
            floor = 2,
        )
        RevisionIndexCodec.decode(RevisionIndexCodec.encode(index)).assert().isEqualTo(index)
        // #1226: records left to delete are written only when there are some, so an index without is as before.
        RevisionIndexCodec.encode(index).decodeToString().assert().doesNotContain("reclaiming")
        val reclaiming = index.copy(reclaiming = listOf(digest))
        RevisionIndexCodec.decode(RevisionIndexCodec.encode(reclaiming)).assert().isEqualTo(reclaiming)
        RevisionIndexCodec.encode(
            reclaiming
        ).decodeToString().assert().endsWith(""""floor":2,"reclaiming":["$digest"]}""")
        listOf(
            "",
            "[]",
            """{"entries":[],"pending":[]}""",
            """{"entries":[],"pending":[],"floor":0}""",
            """{"entries":[{"revision":1,"digest":"zz","publishedAt":"2026-11-01T00:00:00Z"}],"pending":[],"floor":1}""",
            """{"entries":[],"pending":[{"digest":"SHA-256:${"0".repeat(64)}","since":"later"}],"floor":1}""",
            // A 7.x-era SHA-1 digest, without its algorithm.
            """{"entries":[{"revision":1,"digest":"${"0".repeat(
                40
            )}","publishedAt":"2026-11-01T00:00:00Z"}],"pending":[],"floor":1}""",
            """{"entries":[{"revision":1,"digest":"${"0".repeat(
                64
            )}","publishedAt":"2026-11-01T00:00:00Z"}],"pending":[],"floor":1}""",
            """{"entries":[],"pending":[],"floor":1,"x":1}""",
            """{"entries":[],"pending":[],"floor":1,"reclaiming":"x"}""",
            """{"entries":[],"pending":[],"floor":1,"reclaiming":["zz"]}""",
        ).forEach { text ->
            assertThrownBy<SnapshotFormatException> { RevisionIndexCodec.decode(text.encodeToByteArray()) }
        }
        RevisionRecordKeyEncoder.encode(RevisionRecordKey(SnapshotKey.Tenant("acme"), digest))
            .assert().isEqualTo("tenant:acme:SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        ReferenceKeyEncoder.encode(
            ReferenceKey(SnapshotKey.Platform, PolicyId("cosec:root"))
        ).assert().isEqualTo("platform:cosec:root")
    }

    private companion object {
        const val OPEN_CHANGES = 25

        /** A store under [cacheName]'s layout that writes any bytes, as a buggy or earlier writer would. */
        fun <K : Any> rawStore(name: String, keyPrefix: String, cacheName: String, keyEncoder: KeyEncoder<K>) =
            CoSecCacheSpecs.layout(keyPrefix, cacheName).let { layout ->
                StoreSpec.builder<K, ByteArray>(name)
                    .keyPrefix(layout.keyPrefix)
                    .namespace(layout.namespace)
                    .keyEncoder(keyEncoder)
                    .codec(RawCodec)
                    .build()
            }

        val BOOTSTRAP = Publication(
            "bootstrap",
            ProvenanceKind.BOOTSTRAP,
            Actor(TenantId.PLATFORM, PrincipalId("plato"))
        )
        const val SHARED = ""","policies":[{"id":"shared","statements":[{"effect":"allow","action":"order:read"}]}]"""
        const val BINDS_SHARED =
            ""","bindings":[{"id":"reads","subject":"*","app":"order","space":"*","policy":"shared"}]"""
    }
}
