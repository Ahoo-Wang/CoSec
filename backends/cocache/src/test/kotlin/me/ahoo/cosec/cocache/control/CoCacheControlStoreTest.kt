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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cocache.snapshot.RedisTarget
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.cocache.snapshot.rawStore
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.PendingRecord
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
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

    private fun document(revision: Long) = SnapshotDocuments.read(
        """{"schema":2,"revision":$revision,"publishedAt":"2026-11-01T00:00:00Z"}""".encodeToByteArray()
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
        sets = ControlSets(
            node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
            node.runtime.create(ControlCacheSpecs.documentSet(node.keyPrefix)),
            node.runtime.create(ControlCacheSpecs.stagedSet(node.keyPrefix)),
            node.runtime.create(ControlCacheSpecs.changeSet(node.keyPrefix)),
        ),
        leases = node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)),
        changes = changes,
        stages = node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
        candidates = node.runtime.create(me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs.candidate(node.keyPrefix)),
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
     * SCP-10: a back-reference member naming an app no key can name is a format error of the whole set, so every read of
     * it fails, and a removal it could block is refused, until an operator repairs it.
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
                    rawSet(node.keyPrefix)
                ).add(ReferenceKey(SnapshotKey.Platform, shared), listOf("app:a\u0001b@1"))
            }
            formatError { store.referrers(SnapshotKey.Platform, shared) }.message.assert().doesNotContain("\u0001")
            val base = revisions.head(SnapshotKey.Platform)
            formatError {
                revisions.publish(SnapshotKey.Platform, base, document(DocumentKind.PLATFORM, "", ""), BOOTSTRAP)
            }
            revisions.head(SnapshotKey.Platform)!!.digest.assert().isEqualTo(base!!.digest)
        }
    }

    /**
     * `CTL-42`: a back-reference value in the format before 8.0.0-beta.5 is not a set CoCache can read or write. A read
     * fails, naming `cosec-migrate repair-references`; so does a publish that would add to it or a removal it could
     * block, and no head moves.
     */
    @Test
    fun `a back-reference value in the format before beta 5 fails closed, naming the repair`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        val keyPrefix = newKeyPrefix()
        val reference = ReferenceKey(SnapshotKey.Platform, PolicyId("shared"))
        SnapshotNode(backend, keyPrefix).use { node ->
            Revisions(store(node), Clock.systemUTC())
                .publish(SnapshotKey.Platform, null, document(DocumentKind.PLATFORM, "", SHARED), BOOTSTRAP)
        }
        CacheRuntime.builder().backend(backend).build().use { raw ->
            raw.create(
                rawStore("RawReferences", keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME, ReferenceKeyEncoder)
            ).put(reference, """{"tenant:acme":1}""".encodeToByteArray())
        }
        SnapshotNode(backend, keyPrefix).use { node ->
            val store = store(node)
            val revisions = Revisions(store, Clock.systemUTC())
            unrepaired { store.referrers(SnapshotKey.Platform, reference.policy) }
            val beta = SnapshotKey.Tenant("beta")
            unrepaired { revisions.publish(beta, null, document(DocumentKind.TENANT, "beta", BINDS_SHARED), BOOTSTRAP) }
            revisions.head(beta).assert().isNull()
            val base = revisions.head(SnapshotKey.Platform)
            unrepaired {
                revisions.publish(SnapshotKey.Platform, base, document(DocumentKind.PLATFORM, "", ""), BOOTSTRAP)
            }
            revisions.head(SnapshotKey.Platform)!!.digest.assert().isEqualTo(base!!.digest)
        }
    }

    /** R2C-7: a read that fails for another reason, an outage, is a ControlStoreException (R2C-6), without the repair's advice. */
    @Test
    fun `a set that fails to read for another reason does not name the repair`() = runBlocking<Unit> {
        val hooked = HookedBackend(InMemoryBackend())
        SnapshotNode(hooked, newKeyPrefix()).use { node ->
            val store = store(node)
            hooked.failReads = true
            val error = runCatching { store.referrers(SnapshotKey.Platform, PolicyId("shared")) }.exceptionOrNull()
            // The store did not answer (R2C-6): a 503 to the control API, not a format error.
            error.assert().isInstanceOf(ControlStoreException::class.java)
            generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ").assert()
                .doesNotContain("repair-references")
        }
    }

    /** What [block] fails with names the command that converts a store written before 8.0.0-beta.5. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun unrepaired(block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (e: Exception) {
            e
        }
        generateSequence<Throwable>(checkNotNull(error) { "It did not fail." }) { it.cause }
            .mapNotNull { it.message }.joinToString(" | ").assert().contains("cosec-migrate repair-references")
    }

    /** A set at the back-references' keys whose members are written as they are, for members no codec would write. */
    private fun rawSet(keyPrefix: String): SetStoreSpec<ReferenceKey, String> =
        CoSecCacheSpecs.layout(keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME).let { layout ->
            SetStoreSpec.builder<ReferenceKey, String>("RawReferenceSet")
                .keyPrefix(layout.keyPrefix)
                .namespace(layout.namespace)
                .keyEncoder(ReferenceKeyEncoder)
                .elementCodec(StringCodec)
                .build()
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
            formatError { store.allOpenChanges() }
            formatError { store.change(ChangeId("c1")) }
        }
    }

    /**
     * R2C-2, CTL-54: members a write left behind (a removal that failed after a terminal write) are dropped by the next
     * read of the sets, the maintenance pass's: the sets stay a superset, and what they hold too much is made up.
     */
    @Test
    fun `members a write left behind are dropped by the next read`() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        val keyPrefix = newKeyPrefix()
        SnapshotNode(backend, keyPrefix).use { node ->
            SnapshotNode(backend, keyPrefix).use { other ->
                val store = store(node)
                val raw = other.runtime.create(ControlCacheSpecs.changeSet(other.keyPrefix))
                val open = change("c1", ChangeState.DRAFT)
                store.putChange(null, open).assert().isTrue()
                val digest = store.change(ChangeId("c1"))!!.digest
                store.putChange(digest, open.copy(state = ChangeState.ABANDONED)).assert().isTrue()
                // As a removal that failed after the terminal write would leave them.
                raw.suspending().add("doc:$key", listOf("c1"))
                raw.suspending().add("state:DRAFT", listOf("$key#c1"))
                store.allOpenChanges().assert().isEmpty()
                store.openChanges(key).assert().isEmpty()
                raw.suspending().get("state:DRAFT").assert().isEmpty()
                raw.suspending().get("doc:$key").assert().isEmpty()
            }
        }
    }

    /**
     * CTL-54: the open-change sets are trusted only once kept and while no list of the format before 8.0.0-beta.5 is
     * there, whatever members they hold: a control plane keeps them only where there is no old list, and an older one
     * that writes the old list again puts the store back to not kept.
     */
    @Test
    fun `the open-change sets are not kept while the old list is there, whatever they hold`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val lists = node.runtime.create(ControlCacheSpecs.list(node.keyPrefix))
            val store = store(node, lists = lists)
            lists.put("open-changes", mapOf("c0" to Instant.EPOCH))
            store.putChange(null, change("c1", ChangeState.DRAFT)).assert().isTrue()
            store.keepOpenChanges()
            store.openChangesKept().assert().isFalse()
            // The conversion lists what the old list names, keeps the sets and removes the old list.
            store.legacyOpenChanges().assert().containsExactly("c0")
            store.keepConvertedOpenChanges()
            store.openChangesKept().assert().isTrue()
            store.legacyOpenChanges().assert().isNull()
            // An older control plane writes the old list again: not kept, though the marker is there.
            lists.put("open-changes", mapOf("c2" to Instant.EPOCH))
            store.openChangesKept().assert().isFalse()
        }
    }

    /** CTL-54: a new store, with no old list, is kept from the control plane's first keep. */
    @Test
    fun `a new store is kept from the first keep`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val store = store(node)
            store.openChangesKept().assert().isFalse()
            store.keepOpenChanges()
            store.openChangesKept().assert().isTrue()
        }
    }

    @Test
    fun `indexed documents and open changes are listed, terminal ones are not`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val store = store(node)
            store.documents().assert().isEmpty()
            store.putIndex(key, null, RevisionIndex()).assert().isTrue()
            store.documents().assert().containsExactly(key)

            val open = change("c1", ChangeState.DRAFT)
            store.putChange(null, open).assert().isTrue()
            store.putChange(null, change("c2", ChangeState.DRAFT)).assert().isTrue()
            store.allOpenChanges().map { it.id }.assert().containsExactlyInAnyOrder(ChangeId("c1"), ChangeId("c2"))
            val digest = store.change(ChangeId("c1"))!!.digest
            store.putChange(digest, open.copy(state = ChangeState.ABANDONED)).assert().isTrue()
            store.allOpenChanges().map { it.id }.assert().containsExactly(ChangeId("c2"))

            store.openChanges(key).map { it.id }.assert().containsExactly(ChangeId("c2"))
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
                clock,
                revocations = RevocationReader.NONE,
                lockout = LockoutCheck.UNCHECKED,
            )
            val maintenance = ControlMaintenance(store, revisions, changes, Duration.ofMinutes(1))
            store.putIndex(key, null, RevisionIndex()).assert().isTrue()
            reads.clear()

            // Reclamation lists each document's open changes once (R2C-2), and reads each of them once.
            maintenance.reclaim()

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
                StoredCandidate(window, 4, SnapshotDocuments.read(body))
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
                assertThrownBy<ControlStoreException> {
                    runBlocking { writer.putChange(null, change("c1", ChangeState.DRAFT)) }
                }
                val reader = store(reading, clock = clock)
                val raw = CacheRuntime.builder().backend(backend).build()
                    .create(ControlCacheSpecs.changeSet(keyPrefix))
                // c1 is listed and missing: it may be being written, so it is not dropped yet (and there is no change).
                reader.openChanges(key).assert().isEmpty()
                raw.suspending().get("doc:$key").assert().containsExactly("c1")
                clock.now = clock.now.plus(Duration.ofMinutes(59))
                reader.openChanges(key).assert().isEmpty()
                raw.suspending().get("doc:$key").assert().containsExactly("c1")
                clock.now = clock.now.plus(Duration.ofMinutes(2))
                reader.openChanges(key).assert().isEmpty()
                raw.suspending().get("doc:$key").assert().isEmpty()
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
                ControlSets(
                    node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
                    node.runtime.create(ControlCacheSpecs.documentSet(node.keyPrefix)),
                    node.runtime.create(ControlCacheSpecs.stagedSet(node.keyPrefix)),
                    node.runtime.create(ControlCacheSpecs.changeSet(node.keyPrefix)),
                ),
                node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
                node.runtime.create(me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs.candidate(node.keyPrefix)),
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
                ControlSets(
                    node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
                    node.runtime.create(ControlCacheSpecs.documentSet(node.keyPrefix)),
                    node.runtime.create(ControlCacheSpecs.stagedSet(node.keyPrefix)),
                    node.runtime.create(ControlCacheSpecs.changeSet(node.keyPrefix)),
                ),
                node.runtime.create(ControlCacheSpecs.lease(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.changeRequest(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.stage(node.keyPrefix)),
                node.runtime.create(me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs.candidate(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.list(node.keyPrefix)),
                node.runtime.create(ControlCacheSpecs.appList(node.keyPrefix)),
                { },
            )
            node.cache.put(key, document(1))
            assertThrownBy<ControlStoreException> { runBlocking { store.putHead(key, null, document(1)) } }
        }
    }

    @Test
    fun `a failed write throws, and nothing is written`() = runBlocking<Unit> {
        val hooked = HookedBackend(InMemoryBackend())
        SnapshotNode(hooked, newKeyPrefix()).use { node ->
            val store = store(node)
            hooked.failWrites = true
            assertThrownBy<ControlStoreException> { runBlocking { store.putRecord(key, document(1)) } }
            assertThrownBy<ControlStoreException> { runBlocking { store.putHead(key, null, document(1)) } }
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

            // A back-reference set: members added and removed one by one, never a whole value replaced (CTL-25, CTL-44).
            val shared = PolicyId("cosec:shared")
            store.referrers(SnapshotKey.Platform, shared).assert().isEmpty()
            store.addReferrer(SnapshotKey.Platform, shared, ReferrerEntry(key, 3))
            store.addReferrer(SnapshotKey.Platform, shared, ReferrerEntry(key, 4, definesItself = true))
            store.addReferrer(SnapshotKey.Platform, shared, ReferrerEntry(key, 4, definesItself = true))
            store.referrers(SnapshotKey.Platform, shared).assert()
                .containsExactlyInAnyOrder(ReferrerEntry(key, 3), ReferrerEntry(key, 4, definesItself = true))
            store.removeReferrers(SnapshotKey.Platform, shared, setOf(ReferrerEntry(key, 3), ReferrerEntry(key, 9)))
            store.referrers(SnapshotKey.Platform, shared).assert().containsExactly(ReferrerEntry(key, 4, true))
            store.removeReferrers(SnapshotKey.Platform, shared, setOf(ReferrerEntry(key, 4, true)))
            store.removeReferrers(SnapshotKey.Platform, shared, emptySet())
            store.referrers(SnapshotKey.Platform, shared).assert().isEmpty()
            store.listDocuments(emptySet())

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

    /** `CTL-43`: a member is a document key, the revision that indexed it and its flag; anything else is malformed. */
    @Test
    fun `the member codec reads back what it writes and rejects anything else`() {
        listOf(
            ReferrerEntry(SnapshotKey.Tenant("b"), 1),
            ReferrerEntry(SnapshotKey.Platform, 7, definesItself = true),
            ReferrerEntry(SnapshotKey.App("order"), 12),
            // A tenant id may hold '@': the revision is after the last one.
            ReferrerEntry(SnapshotKey.Tenant("a@b"), 2),
        ).forEach { entry ->
            ReferrerMemberCodec.decode(ReferrerMemberCodec.encode(entry)).assert().isEqualTo(entry)
        }
        listOf(
            "",
            "tenant:b",
            "tenant:b@",
            "tenant:b@0",
            "tenant:b@-1",
            "tenant:b@1.5",
            "tenant:b@x",
            "tenant:b@1#mine",
            "@1",
            "policies@1",
            // An app no key can name (SCP-10).
            "app:a\u0001b@1",
            // The object the format before 8.0.0-beta.5 wrote.
            """{"tenant:b":1}""",
        ).forEach { text ->
            assertThrownBy<SnapshotFormatException> { ReferrerMemberCodec.decode(text.encodeToByteArray()) }
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
