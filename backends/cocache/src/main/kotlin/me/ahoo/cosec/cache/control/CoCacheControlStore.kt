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

import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.createLocked
import me.ahoo.cosec.cache.shadow.ShadowCacheSpecs
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore.Companion.cocacheDigest
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore.Companion.snapshotDigest
import me.ahoo.cosec.cache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The [ControlStore] over CoCache and Redis (RFC 0007 §5.2). The heads are the snapshot documents the data plane
 * reads ([PolicySnapshotCache], one key per document); the records, indexes, back-references and leases are the
 * `Store`s of [ControlCacheSpecs]. Digests are CoCache's, SHA-256 with its algorithm.
 *
 * Writes are `putIfMatch`, one Lua script each: the head only over the digest it was read at, a record only where
 * there is none. A write whose reply is lost is resolved by reading the digest back, never by writing again:
 * `putIfMatch` is not idempotent. Nothing waits for replicas (RFC 0007 Q27): a write lost in a failover shows up as
 * a broken chain or a revision regression.
 *
 * Every read is a conditional read without a digest, so it answers from Redis, not from a local copy, and a failed
 * read throws (a `Store`'s conditional read always does). Nothing here blocks the caller: it awaits CoCache's async view.
 */
class CoCacheControlStore internal constructor(
    private val heads: Store<SnapshotKey, SnapshotDocument>,
    private val records: Store<RevisionRecordKey, SnapshotDocument>,
    private val indexes: Store<SnapshotKey, RevisionIndex>,
    private val references: Store<ReferenceKey, Map<SnapshotKey, Long>>,
    private val leases: Store<String, String>,
    private val changes: Store<ChangeId, ChangeRequest>,
    private val stages: Store<SnapshotKey, StageSlot>,
    /** The staged candidates the data plane's shadow listeners read (RFC 0007 §6.4). */
    private val candidates: Store<SnapshotKey, StoredCandidate>,
    /** The document keys and the ids of changes that may be open ([documents], [openChanges]). */
    private val lists: Store<String, Map<String, Instant>>,
    /** The app documents there are ([apps], RC1-4). */
    private val appLists: Store<String, AppList>,
    /** Told after a head write, typically `RedisSnapshotDocumentStore.notifyChanged`, so this instance reloads at once. */
    private val onPublished: (SnapshotKey) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    /**
     * How long a listed change may be missing before [openChanges] drops it (`cosec.control.revisions.orphan-age`):
     * a change is listed before it is written, so one being written right now is missing for a moment, and one whose
     * first write failed is missing for good.
     */
    private val missingGrace: Duration = DEFAULT_MISSING_GRACE,
) : ControlStore {
    override fun digestOf(document: SnapshotDocument): SnapshotDigest = digest(SnapshotDocumentCodec, document)

    override suspend fun head(key: SnapshotKey): StoredDocument? =
        read(heads, key)?.let { (value, digest) -> StoredDocument(value, digest) }

    override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite {
        val mine = digestOf(document)
        val written = when (val result = put(heads, key, expected, document, mine)) {
            is Put.Written -> HeadWrite.Written(mine)
            is Put.Conflict -> HeadWrite.Conflict(result.current)
        }
        if (written is HeadWrite.Written) {
            onPublished(key)
        }
        return written
    }

    override suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument? =
        read(records, RevisionRecordKey(key, digest))?.first

    override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest {
        val mine = digestOf(document)
        when (val result = put(records, RevisionRecordKey(key, mine), null, document, mine)) {
            is Put.Written -> Unit
            // The address is the digest: another value there is not this document, which no writer can produce.
            is Put.Conflict -> check(result.current == mine) {
                "The revision record of [$key] at $mine holds a value with digest ${result.current}."
            }
        }
        return mine
    }

    override suspend fun removeRecord(key: SnapshotKey, digest: SnapshotDigest) {
        records.async().delete(RevisionRecordKey(key, digest)).await()
    }

    override suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>? =
        read(indexes, key)?.let { (value, digest) -> Versioned(value, digest) }

    /** A first index write lists [key] before it is written, so [documents] never misses a document. */
    override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
        if (expected == null) {
            val entry = SnapshotKeyEncoder.encode(key)
            updateList(DOCUMENTS) { if (entry in it) it else it + (entry to clock.instant()) }
        }
        return put(indexes, key, expected, index, digest(RevisionIndexCodec, index)) is Put.Written
    }

    override suspend fun documents(): Set<SnapshotKey> =
        read(lists, DOCUMENTS)?.first.orEmpty().keys.mapNotNullTo(LinkedHashSet()) { SnapshotKeyEncoder.decode(it) }

    override suspend fun apps(): Versioned<AppList>? =
        read(appLists, APP_LIST)?.let { (value, digest) -> Versioned(value, digest) }

    override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean =
        put(appLists, APP_LIST, expected, apps, digest(AppListCodec, apps)) is Put.Written

    override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Versioned<Map<SnapshotKey, Long>>? =
        read(references, ReferenceKey(target, policy))?.let { (value, digest) -> Versioned(value, digest) }

    /**
     * Emptied referrers are written as empty, not deleted: the next writer always has a digest to name, and absent means
     * never written.
     */
    override suspend fun putReferrers(
        target: SnapshotKey,
        policy: PolicyId,
        expected: SnapshotDigest?,
        referrers: Map<SnapshotKey, Long>,
    ): Boolean = put(
        references,
        ReferenceKey(target, policy),
        expected,
        referrers,
        digest(ReferrersCodec, referrers)
    ) is Put.Written

    override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? =
        read(changes, id)?.let { (value, digest) -> Versioned(value, digest) }

    /**
     * A change is listed open, with the time, before its first write and unlisted after the write that makes it
     * terminal. When that removal fails, the change is terminal and still listed: [openChanges] drops it the next time
     * it is read.
     */
    override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean {
        if (expected == null) {
            val id = change.id.value
            updateList(OPEN_CHANGES) { if (id in it) it else it + (id to clock.instant()) }
        }
        val written = put(changes, change.id, expected, change, digest(ChangeRequestCacheCodec, change)) is Put.Written
        if (written && change.state.terminal) {
            updateList(OPEN_CHANGES) { it - change.id.value }
        }
        return written
    }

    /**
     * The listed changes that are not terminal. A terminal one is dropped; so is one missing for longer than
     * [missingGrace], whose first write failed. One missing for less may be being written right now: it stays. One that
     * does not decode fails this: it is not known to be terminal.
     */
    override suspend fun openChanges(): Set<ChangeId> {
        val listed = read(lists, OPEN_CHANGES)?.first.orEmpty()
        val expired = clock.instant().minus(missingGrace)
        val closed = listed.filterTo(HashMap()) { (id, listedAt) ->
            read(changes, ChangeId(id))?.first?.state?.terminal ?: listedAt.isBefore(expired)
        }.keys
        if (closed.isNotEmpty()) {
            updateList(OPEN_CHANGES) { it - closed }
        }
        return (listed.keys - closed).mapTo(LinkedHashSet(), ::ChangeId)
    }

    override suspend fun stage(key: SnapshotKey): Versioned<StageSlot>? =
        read(stages, key)?.let { (value, digest) -> Versioned(value, digest) }

    override suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean =
        put(stages, key, expected, slot, digest(StageSlotCodec, slot)) is Put.Written

    override suspend fun candidate(key: SnapshotKey): StoredCandidate? = read(candidates, key)?.first

    override suspend fun putCandidate(key: SnapshotKey, candidate: StoredCandidate) {
        candidates.async().put(key, candidate).await()
    }

    /** `deleteIfMatch` over the digest of the candidate of [window] as read, so another window's is never removed. */
    override suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean =
        deleteIf(candidates, key) { it.window == window }

    /**
     * Writes the lease where there is none (`putIfMatch` over nothing). [holder] taking its own lease again renews it for
     * the full [duration], as `InMemoryControlStore` does: a `true` that left the old expiry would let a task that
     * believes it holds the lease for [duration] lose it within moments. The renewal is `putIfMatch` over the lease as
     * read, so one that expired and was taken by another holder in between is not overwritten: it is read again.
     */
    override suspend fun acquireLease(name: String, holder: String, duration: Duration): Boolean {
        val async = leases.async()
        repeat(MAX_LIST_ATTEMPTS) {
            if (async.putIfMatch(name, null, holder, duration).await() is ConditionalPut.Written) {
                return true
            }
            val (current, digest) = read(leases, name) ?: return@repeat
            if (current != holder) {
                return false
            }
            if (async.putIfMatch(name, cocacheDigest(digest), holder, duration).await() is ConditionalPut.Written) {
                return true
            }
        }
        throw IllegalStateException(
            "The lease [$name] kept changing while it was taken; gave up after $MAX_LIST_ATTEMPTS attempts."
        )
    }

    /**
     * `deleteIfMatch` over the digest of [holder]'s lease as read: one that expired after the read and another instance
     * took has another value, and stays. [holder] taking it again in between is the one case it removes after the read,
     * and that lease is its own.
     */
    override suspend fun releaseLease(name: String, holder: String) {
        deleteIf(leases, name) { it == holder }
    }

    /**
     * Removes [key] if its value, read now, is [wanted], and only while it still is (`deleteIfMatch`). A conflict is
     * read again: the value that replaced it may be wanted too. A removal whose outcome is unknown is read back: gone, or
     * another value, means it is not this caller's to remove any more.
     */
    private suspend fun <K : Any, V : Any> deleteIf(store: Store<K, V>, key: K, wanted: (V) -> Boolean): Boolean {
        repeat(MAX_LIST_ATTEMPTS) {
            val (value, digest) = read(store, key) ?: return false
            if (!wanted(value)) {
                return false
            }
            val deleted = try {
                store.async().deleteIfMatch(key, cocacheDigest(digest)).await()
            } catch (error: CacheWriteException) {
                return removedAfter(store, key, digest, error)
            }
            if (deleted == ConditionalDelete.Deleted) {
                return true
            }
        }
        throw IllegalStateException(
            "[$key] kept changing while it was removed; gave up after $MAX_LIST_ATTEMPTS attempts."
        )
    }

    /**
     * Whether a `deleteIfMatch` of [key] over [digest] that failed with [error] removed it: read back when its outcome
     * is unknown. Still [digest] means it did not, and the failure is the answer.
     */
    private suspend fun <K : Any, V : Any> removedAfter(
        store: Store<K, V>,
        key: K,
        digest: SnapshotDigest,
        error: CacheWriteException,
    ): Boolean {
        val after = if (error.outcomeUnknown) read(store, key) else throw error
        if (after?.second == digest) throw error
        return after == null
    }

    /** Read, change, `putIfMatch`: retried while another writer changed the list in between. */
    private suspend fun updateList(name: String, next: (Map<String, Instant>) -> Map<String, Instant>) {
        repeat(MAX_LIST_ATTEMPTS) {
            val current = read(lists, name)
            val value = next(current?.first.orEmpty())
            if (value == current?.first) {
                return
            }
            if (put(lists, name, current?.second, value, digest(ListedCodec, value)) is Put.Written) {
                return
            }
        }
        throw IllegalStateException(
            "The control list [$name] kept changing; gave up after $MAX_LIST_ATTEMPTS attempts."
        )
    }

    private sealed interface Put {
        object Written : Put

        class Conflict(val current: SnapshotDigest?) : Put
    }

    /**
     * `putIfMatch`. A write that failed with an unknown outcome is resolved by reading back: [mine] means it landed;
     * still [expected] means it did not, and the failure is the answer; anything else means another writer got there.
     * A write CoCache knows was not executed (`outcomeUnknown` false, such as a command rejected before it was sent) is
     * not read back: the failure is the answer, even when another writer stored the same bytes in between.
     */
    private suspend fun <K : Any, V : Any> put(
        cache: Store<K, V>,
        key: K,
        expected: SnapshotDigest?,
        value: V,
        mine: SnapshotDigest
    ): Put {
        val async = cache.async()
        val result = try {
            async.putIfMatch(key, expected?.let(::cocacheDigest), value).await()
        } catch (error: CacheWriteException) {
            val read = if (error.outcomeUnknown) readBack(cache, key, mine) else null
            val current = (read as? ConditionalGet.Present)?.let { snapshotDigest(it.digest) }
            return when {
                read == ConditionalGet.Unchanged -> Put.Written
                read == null || current == expected -> throw error
                else -> Put.Conflict(current)
            }
        }
        return when (result) {
            is ConditionalPut.Written -> Put.Written
            is ConditionalPut.Conflict -> Put.Conflict(result.current?.let(::snapshotDigest))
        }
    }

    /** What [key] holds now, compared with [mine]; null when Redis cannot say, so the write's own failure stands. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private suspend fun <K : Any, V : Any> readBack(
        cache: Store<K, V>,
        key: K,
        mine: SnapshotDigest
    ): ConditionalGet<V>? =
        try {
            cache.async().getIfChanged(key, cocacheDigest(mine)).await()
        } catch (_: Exception) {
            null
        }

    private suspend fun <K : Any, V : Any> read(cache: Store<K, V>, key: K): Pair<V, SnapshotDigest>? =
        when (val read = cache.async().getIfChanged(key, null).await()) {
            is ConditionalGet.Present -> read.value to snapshotDigest(read.digest)
            ConditionalGet.Unchanged -> error("An unconditional read of [$key] was answered as unchanged.")
            ConditionalGet.Absent -> null
        }

    companion object {
        private const val DOCUMENTS = "documents"
        private const val OPEN_CHANGES = "open-changes"
        private const val APP_LIST = "all"
        private const val MAX_LIST_ATTEMPTS = 16

        /** As `cosec.control.revisions.orphan-age`'s default. */
        val DEFAULT_MISSING_GRACE: Duration = Duration.ofHours(1)

        /** The digest CoCache computes for [value] stored with [codec]. */
        fun <V> digest(codec: Codec<V>, value: V): SnapshotDigest = snapshotDigest(
            Digest.ofPayload(codec.encode(value))
        )

        /**
         * Creates the control caches under [keyPrefix] through [factory], with CoSec's locks ([CoSecCacheSpecs.locks]),
         * next to the snapshot heads [heads].
         */
        fun create(
            factory: CoCacheFactory,
            heads: PolicySnapshotCache,
            keyPrefix: String = CoSec.COSEC,
            onPublished: (SnapshotKey) -> Unit = {},
            missingGrace: Duration = DEFAULT_MISSING_GRACE,
        ): CoCacheControlStore = CoCacheControlStore(
            heads = heads,
            records = factory.createLocked(ControlCacheSpecs.revisionRecord(keyPrefix)),
            indexes = factory.createLocked(ControlCacheSpecs.revisionIndex(keyPrefix)),
            references = factory.createLocked(ControlCacheSpecs.referenceIndex(keyPrefix)),
            leases = factory.createLocked(ControlCacheSpecs.lease(keyPrefix)),
            changes = factory.createLocked(ControlCacheSpecs.changeRequest(keyPrefix)),
            stages = factory.createLocked(ControlCacheSpecs.stage(keyPrefix)),
            candidates = factory.createLocked(ShadowCacheSpecs.candidate(keyPrefix)),
            lists = factory.createLocked(ControlCacheSpecs.list(keyPrefix)),
            appLists = factory.createLocked(ControlCacheSpecs.appList(keyPrefix)),
            onPublished = onPublished,
            missingGrace = missingGrace,
        )
    }
}
