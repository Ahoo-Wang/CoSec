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

import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import java.time.Duration

/**
 * The [ControlStore] over CoCache and Redis (RFC 0007 §5.2). The heads are the snapshot documents the data plane
 * reads ([PolicySnapshotCache], one key per document); the records, indexes, back-references and leases are the
 * caches of [ControlCacheSpecs].
 *
 * Writes are `putIfMatch`, one Lua script each (CoCache 6.7), as [me.ahoo.cosec.cache.snapshot.RedisSnapshotPublisher]
 * writes: the head only over the digest it was read at, a record only where there is none. A write whose reply is lost
 * is resolved by reading the digest back, never by writing again: `putIfMatch` is not idempotent. Nothing waits for
 * replicas (RFC 0007 Q27): a write lost in a failover shows up as a broken chain or a revision regression.
 *
 * Every read is a conditional read without a digest, so it answers from Redis, not from a local copy, and a failed
 * read throws ([ControlCacheSpecs] pins `ReadFailureMode.THROW`). Nothing here blocks the caller: it awaits CoCache's
 * async view.
 */
class CoCacheControlStore(
    private val heads: Cache<SnapshotKey, SnapshotDocument>,
    private val records: Cache<RevisionRecordKey, SnapshotDocument>,
    private val indexes: Cache<SnapshotKey, RevisionIndex>,
    private val references: Cache<ReferenceKey, Set<SnapshotKey>>,
    private val leases: Cache<String, String>,
    private val changes: Cache<ChangeId, ChangeRequest>,
    private val stages: Cache<SnapshotKey, StageSlot>,
    /** Told after a head write, typically `RedisSnapshotDocumentStore.notifyChanged`, so this instance reloads at once. */
    private val onPublished: (SnapshotKey) -> Unit = {},
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
        records.async().invalidate(RevisionRecordKey(key, digest)).await()
    }

    override suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>? =
        read(indexes, key)?.let { (value, digest) -> Versioned(value, digest) }

    override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean =
        put(indexes, key, expected, index, digest(RevisionIndexCodec, index)) is Put.Written

    override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Versioned<Set<SnapshotKey>>? =
        read(references, ReferenceKey(target, policy))?.let { (value, digest) -> Versioned(value, digest) }

    /** An emptied set is written as empty, never deleted: a delete cannot be conditional, and could drop a new referrer. */
    override suspend fun putReferrers(
        target: SnapshotKey,
        policy: PolicyId,
        expected: SnapshotDigest?,
        referrers: Set<SnapshotKey>,
    ): Boolean = put(
        references,
        ReferenceKey(target, policy),
        expected,
        referrers,
        digest(SnapshotKeySetCodec, referrers)
    ) is Put.Written

    override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? =
        read(changes, id)?.let { (value, digest) -> Versioned(value, digest) }

    override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean =
        put(changes, change.id, expected, change, digest(ChangeRequestCacheCodec, change)) is Put.Written

    override suspend fun stage(key: SnapshotKey): Versioned<StageSlot>? =
        read(stages, key)?.let { (value, digest) -> Versioned(value, digest) }

    override suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean =
        put(stages, key, expected, slot, digest(StageSlotCodec, slot)) is Put.Written

    override suspend fun acquireLease(name: String, holder: String, duration: Duration): Boolean {
        val result = leases.async().putIfMatch(name, null, holder, duration).await()
        if (result is ConditionalPut.Written) {
            return true
        }
        return (read(leases, name)?.first) == holder
    }

    private sealed interface Put {
        object Written : Put

        class Conflict(val current: SnapshotDigest?) : Put
    }

    /**
     * `putIfMatch`. A write that failed with an unknown outcome is resolved by reading back: [mine] means it landed;
     * still [expected] means it did not, and the failure is the answer; anything else means another writer got there.
     */
    private suspend fun <K : Any, V : Any> put(
        cache: Cache<K, V>,
        key: K,
        expected: SnapshotDigest?,
        value: V,
        mine: SnapshotDigest
    ): Put {
        val async = cache.async()
        val result = try {
            async.putIfMatch(key, expected?.let { Digest(it.toByteArray()) }, value).await()
        } catch (error: CacheWriteException) {
            val read = try {
                async.getIfChanged(key, Digest(mine.toByteArray())).await()
            } catch (_: Exception) {
                throw error
            }
            val current = (read as? ConditionalGet.Present)?.let { SnapshotDigest(it.digest.sha1) }
            return when {
                read == ConditionalGet.Unchanged -> Put.Written
                current == expected -> throw error
                else -> Put.Conflict(current)
            }
        }
        return when (result) {
            is ConditionalPut.Written -> Put.Written
            is ConditionalPut.Conflict -> Put.Conflict(result.current?.let { SnapshotDigest(it.sha1) })
            else -> error("Unknown conditional write result $result")
        }
    }

    private suspend fun <K : Any, V : Any> read(cache: Cache<K, V>, key: K): Pair<V, SnapshotDigest>? =
        when (val read = cache.async().getIfChanged(key, null).await()) {
            is ConditionalGet.Present -> read.value to SnapshotDigest(read.digest.sha1)
            ConditionalGet.Unchanged -> error("An unconditional read of [$key] was answered as unchanged.")
            else -> null
        }

    companion object {
        /** The digest CoCache computes for [value] stored with [codec]. */
        fun <V> digest(codec: Codec<V>, value: V): SnapshotDigest = SnapshotDigest(
            Digest.of(Envelope.Scalar(codec.encode(value))).sha1
        )

        /** Creates the control caches under [keyPrefix] in [runtime], next to the snapshot heads [heads]. */
        fun create(
            runtime: CacheRuntime,
            heads: PolicySnapshotCache,
            keyPrefix: String = CoSec.COSEC,
            onPublished: (SnapshotKey) -> Unit = {},
        ): CoCacheControlStore = CoCacheControlStore(
            heads = heads,
            records = runtime.create(ControlCacheSpecs.revisionRecord(keyPrefix)),
            indexes = runtime.create(ControlCacheSpecs.revisionIndex(keyPrefix)),
            references = runtime.create(ControlCacheSpecs.referenceIndex(keyPrefix)),
            leases = runtime.create(ControlCacheSpecs.lease(keyPrefix)),
            changes = runtime.create(ControlCacheSpecs.changeRequest(keyPrefix)),
            stages = runtime.create(ControlCacheSpecs.stage(keyPrefix)),
            onPublished = onPublished,
        )
    }
}
