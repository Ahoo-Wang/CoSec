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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.RevisionLog.log
import me.ahoo.cosec.control.store.RevisionLog.warn
import me.ahoo.cosec.document.ParentRevision
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The records [Revisions] keeps, and the chain they make (RFC 0007 §5.4, §5.7): `verify-chain`, and reclamation of
 * what the [retention] no longer keeps, one instance at a time under a lease.
 */
internal class Retention(
    private val store: ControlStore,
    private val writes: RevisionWrites,
    private val clock: Clock,
    private val retention: RetentionPolicy,
    /** The head of a document, as [Revisions.head] reads it. */
    private val head: suspend (SnapshotKey) -> Head?,
    /** A stored document decoded, as [Revisions.decode] decodes it. */
    private val decode: (SnapshotKey, SnapshotDocument) -> PolicyDocument,
    /** The retained revisions of a document, as [Revisions.history] lists them, and the head's digest it read. */
    private val history: suspend (SnapshotKey) -> Pair<List<RevisionEntry>, SnapshotDigest?>,
) {
    private val holder: String = UUID.randomUUID().toString()

    /**
     * Walks the `parent` chain of [key]'s head (`verify-chain`, RFC 0007 §5.4): every parent at or above the index
     * floor must have its record, holding that revision. A missing one is a lost write.
     *
     * The floor read when the walk begins (a store that cannot read the index fails the check, as before) is only the
     * answer for what it already covers (#1532): the walk runs beside the control plane, whose [reclaim] moves the
     * floor past a revision before it deletes the record. A record missing at or above that floor is judged by the
     * floor read again, after the miss. The floor only rises, so that read is at least the floor at the miss: a record
     * gone from below it was reclaimed, however long the walk took, and one gone from at or above it was never
     * reclaimable and is lost.
     */
    suspend fun verifyChain(key: SnapshotKey): ChainCheck {
        val head = head(key) ?: return ChainCheck.Intact(0)
        val floor = currentFloor(key)
        if (store.record(key, head.digest) == null) {
            return ChainCheck.Broken(ParentRevision(head.revision, head.digest.toString()))
        }
        var document = head.document
        while (true) {
            val parent = document.provenance?.parent ?: return ChainCheck.Intact(document.revision)
            val record = store.record(key, SnapshotDigest.parse(parent.digest))
            if (record == null) {
                return if (parent.revision < floor || parent.revision < currentFloor(key)) {
                    ChainCheck.Intact(document.revision)
                } else {
                    ChainCheck.Broken(parent)
                }
            }
            document = decode(key, record)
            if (document.revision != parent.revision) {
                return ChainCheck.Broken(parent)
            }
        }
    }

    /** The index floor as it is now; an index that is gone reads as 1, so that nothing missing is taken for reclaimed. */
    private suspend fun currentFloor(key: SnapshotKey): Long = store.index(key)?.value?.floor ?: 1

    /**
     * Removes the records of [key] that the [retention] no longer keeps (RFC 0007 §5.7): beyond the [RetentionPolicy.maxCount]
     * most recent revisions and older than [RetentionPolicy.minAge], unless [keep] names them (the bases and rollback
     * targets of changes still open), and the orphans older than [RetentionPolicy.orphanAge]. One instance at a time:
     * without the lease it removes nothing.
     *
     * The index is written first, so a listed revision never misses its record; what it takes out goes to
     * [RevisionIndex.reclaiming] in that same write, and is dropped from there once deleted (#1226). A deletion that fails
     * is tried again by the next pass; one of a record that became the head, a listed revision or a kept one since is
     * not tried at all.
     */
    suspend fun reclaim(key: SnapshotKey, keep: Set<SnapshotDigest> = emptySet()): Reclaimed {
        if (!reclaimable(key)) {
            return Reclaimed.NOTHING
        }
        val lease = "reclaim:$key"
        if (!store.acquireLease(lease, holder, retention.leaseDuration)) {
            return Reclaimed.NOTHING
        }
        try {
            return reclaimHeld(key, keep)
        } finally {
            // Done with the document: its holder deletes the lease, only while it still holds it (CTL-26, storage.md).
            withContext(NonCancellable) { release(lease) }
        }
    }

    /**
     * Whether [key] can have anything to reclaim: more revisions than [RetentionPolicy.maxCount], a pending record (an
     * orphan once old), or a record an earlier pass left to delete. Only an index that agrees with the head says so: one
     * that is missing, behind the head or on another branch ([HistoryRepair.agrees]) may hide revisions, and is read
     * through [history] as before. So a pass reads one index and one head of a document with nothing to reclaim, takes
     * no lease and writes nothing.
     */
    private suspend fun reclaimable(key: SnapshotKey): Boolean {
        val index = store.index(key)?.value ?: return true
        if (index.entries.size > retention.maxCount || index.pending.isNotEmpty() || index.reclaiming.isNotEmpty()) {
            return true
        }
        val stored = store.head(key) ?: return true
        return !HistoryRepair.agrees(index, stored)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun release(lease: String) {
        try {
            store.releaseLease(lease, holder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "The lease [$lease] could not be released; it expires on its own." }
        }
    }

    private suspend fun reclaimHeld(key: SnapshotKey, keep: Set<SnapshotDigest>): Reclaimed {
        val (entries, head) = history(key)
        val now = clock.instant()
        val recent = entries.takeLast(retention.maxCount).map { it.digest }.toSet()
        val retained = entries.map { it.digest }
        val removed = entries.filter { entry ->
            entry.digest !in recent && entry.digest !in keep && entry.digest != head &&
                entry.publishedAt.isBefore(now.minus(retention.minAge))
        }
        var orphans: List<SnapshotDigest> = emptyList()
        var reclaiming: List<SnapshotDigest> = emptyList()
        val updated = writes.updateIndex(key) { index ->
            val chain = index.entries.map { it.digest }.toSet()
            orphans = index.pending.filter { pending ->
                pending.digest !in chain && pending.digest !in keep && pending.digest != head &&
                    pending.since.isBefore(now.minus(retention.orphanAge))
            }.map { it.digest }
            val entries = index.entries.filter { entry -> removed.none { it.digest == entry.digest } }
            val pending = index.pending.filter { it.digest !in orphans }
            // What the verified history keeps is live too: a digest it lists is never left to delete (#1536).
            val live = entries.map { it.digest }.toSet() + pending.map { it.digest } + keep + listOfNotNull(head) +
                retained.filter { digest -> removed.none { it.digest == digest } }
            // What an earlier pass could not delete, unless it is live again; then what this pass takes out.
            reclaiming = (index.reclaiming + removed.map { it.digest } + orphans).distinct().filter { it !in live }
            index.copy(
                entries = entries,
                pending = pending,
                floor = maxOf(index.floor, (removed.maxOfOrNull { it.revision } ?: 0) + 1),
                reclaiming = reclaiming,
            )
        }
        if (!updated) {
            warn("[$key]: the index kept changing; nothing was reclaimed")
            return Reclaimed.NOTHING
        }
        // The index no longer lists them, so a failure here leaves unlisted records behind, never a listed one missing.
        val (deleted, failed) = reclaiming.partition { delete(key, it) }
        if (deleted.isNotEmpty()) {
            writes.updateIndex(key) { index -> index.copy(reclaiming = index.reclaiming - deleted.toSet()) } ||
                warn("[$key]: the records deleted stay listed to reclaim; the next pass deletes them again")
        }
        return Reclaimed(removed.map { it.digest }, orphans, deleted, failed)
    }

    /** Deletes the record of [key] at [digest]; `false` when that failed, which the next pass tries again. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun delete(key: SnapshotKey, digest: SnapshotDigest): Boolean = try {
        store.removeRecord(key, digest)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "[$key]: the record $digest could not be deleted; the next pass tries again." }
        false
    }
}
