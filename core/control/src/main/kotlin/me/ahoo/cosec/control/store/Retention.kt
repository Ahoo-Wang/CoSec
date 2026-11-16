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
    /** The retained revisions of a document, as [Revisions.history] lists them. */
    private val history: suspend (SnapshotKey) -> List<RevisionEntry>,
) {
    private val holder: String = UUID.randomUUID().toString()

    /**
     * Walks the `parent` chain of [key]'s head (`verify-chain`, RFC 0007 §5.4): every parent at or above the index
     * floor must have its record, holding that revision. A missing one is a lost write.
     */
    suspend fun verifyChain(key: SnapshotKey): ChainCheck {
        val head = head(key) ?: return ChainCheck.Intact(0)
        val floor = store.index(key)?.value?.floor ?: 1
        if (store.record(key, head.digest) == null) {
            return ChainCheck.Broken(ParentRevision(head.revision, head.digest.toString()))
        }
        var document = head.document
        while (true) {
            val parent = document.provenance?.parent ?: return ChainCheck.Intact(document.revision)
            val record = store.record(key, SnapshotDigest.parse(parent.digest))
            if (record == null) {
                return if (parent.revision >= floor) ChainCheck.Broken(parent) else ChainCheck.Intact(document.revision)
            }
            document = decode(key, record)
            if (document.revision != parent.revision) {
                return ChainCheck.Broken(parent)
            }
        }
    }

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
        if (!store.acquireLease("reclaim:$key", holder, retention.leaseDuration)) {
            return Reclaimed.NOTHING
        }
        val entries = history(key)
        val now = clock.instant()
        val recent = entries.takeLast(retention.maxCount).map { it.digest }.toSet()
        val head = head(key)?.digest
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
            val live = entries.map { it.digest }.toSet() + pending.map { it.digest } + keep + listOfNotNull(head)
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
