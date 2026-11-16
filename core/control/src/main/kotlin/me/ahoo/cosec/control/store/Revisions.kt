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
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.store.RevisionLog.log
import me.ahoo.cosec.control.store.RevisionLog.warn
import me.ahoo.cosec.document.ParentRevision
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.Provenance
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Revisions of the platform, app and tenant documents over a [ControlStore] (RFC 0007 §5.4–§5.8).
 *
 * [publish] writes in the order that never loses content and never lets the data plane see anything but the old or
 * the new head:
 * 1. the record is registered as pending in the index, then written, create-only (`putIfMatch(expected = null)`);
 * 2. the back-references the candidate adds are added, each with the candidate's revision (the index stays a superset
 *    of the true references), and an app document is listed among the apps ([ControlStore.apps], RC1-4) if it is not;
 * 3. the caller's precondition is read, and the head is replaced with `putIfMatch(expected = base digest)`: one key,
 *    one atomic step (P7). A conflict leaves the head as someone else wrote it and is reported, never retried on the
 *    new content (RFC 0007 §5.5);
 * 4. the index gains the revision, and back-references only the base had are removed, unless a later revision has
 *    indexed them again since (#1207); the platform's first revision marks the app list complete when it names every
 *    app document the store lists.
 *
 * Steps 3 and 4 run to the end even when the caller is cancelled, and a failure in step 4 is only logged, a store's
 * own timeout among them (#1263): the head write is the commit point.
 *
 * A failure after step 3 loses no decision and no content: the index is rebuilt from the chain ([history]), extra
 * references block at most a deletion. The records of conflicted publishes are reclaimed as orphans ([reclaim]).
 */
// B4: revisions split by operation
@Suppress("TooManyFunctions")
@InternalCoSecApi
class Revisions(
    private val store: ControlStore,
    private val clock: Clock = Clock.systemUTC(),
    val retention: RetentionPolicy = RetentionPolicy(),
    /** Attempts of a conditional replace of the index or the back-references before it gives up. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val holder: String = UUID.randomUUID().toString()

    private val writes = RevisionWrites(store, maxAttempts)

    private val referrerIndex = ReferrerIndex(store, writes, ::head)

    /** The head of [key], read from the store of record. */
    suspend fun head(key: SnapshotKey): Head? {
        val stored = store.head(requireDocumentKey(key)) ?: return null
        return Head(key, decode(key, stored.document), stored)
    }

    /**
     * Publishes [candidate] over [base] (`null`: [key] has no document yet) for [publication]. The candidate's own
     * revision, time and provenance are replaced: the revision follows the base's, the parent is the base.
     *
     * [precondition] is read right before the head write, the last step that may still refuse it: `false` writes nothing
     * and answers [PublishOutcome.Conflicted], as a moved base would (a change that lost its staging slot, review L2).
     * Once the head is written, the rest does not respond to cancellation, and [afterWrite] runs in it: what the caller
     * must do once the revision is in effect. A failure of this class's own steps after the write is only logged, and
     * the revision is [PublishOutcome.Published]: the references it adds were indexed before the write, so a reference
     * it fails to remove leaves the index a superset, and [history] rebuilds the revision index from the chain. What
     * [afterWrite] throws is thrown.
     */
    suspend fun publish(
        key: SnapshotKey,
        base: Head?,
        candidate: PolicyDocument,
        publication: Publication,
        precondition: suspend () -> Boolean = { true },
        afterWrite: suspend (PublishOutcome.Published) -> Unit = {},
    ): PublishOutcome {
        requireDocumentKey(key)
        require(base == null || base.key == key) { "The base of [$key] is a head of [$key], not of [${base?.key}]." }
        val (kind, owner) = ownerOf(key)
        require(candidate.kind == kind && candidate.owner == owner) { "The candidate is not a document of [$key]." }
        referrerIndex.referenced(key, base, candidate)?.let { return it }
        val document = next(base, candidate, publication)
        val revision = document.revision
        val publishedAt = document.publishedAt
        val stored = SnapshotDocuments.of(document)
        val digest = store.digestOf(stored)
        writes.updateIndex(key) { it.copy(pending = it.pending + PendingRecord(digest, publishedAt)) } ||
            warn("[$key] revision $revision: its record could not be registered as pending; an orphan would stay")
        check(store.putRecord(key, stored) == digest) { "The store addressed the record of [$key] by another digest." }
        val before = base?.let { referrerIndex.references(it.document) }.orEmpty()
        val after = referrerIndex.references(document)
        for ((target, policy) in after - before) {
            check(writes.updateReferrers(target, policy) { referrerIndex.indexed(it, key, revision) }) {
                "[$key]: the reference to [$policy] of [$target] could not be indexed; the head was not moved."
            }
        }
        if (key is SnapshotKey.App) {
            // Before the head moves, whether this is its first revision or the list was lost: no app has a head the
            // app list does not name (RC1-4).
            check(writes.updateApps { it.copy(entries = it.entries + key.appId) }) {
                "[$key] could not be listed among the app documents; the head was not moved."
            }
        }
        return withContext(NonCancellable) {
            if (!precondition()) {
                log.info { "[$key] revision $revision was not written: its precondition no longer holds." }
                return@withContext PublishOutcome.Conflicted(base?.digest)
            }
            when (val write = store.putHead(key, base?.digest, stored)) {
                is HeadWrite.Conflict -> {
                    log.info { "[$key] changed since revision ${base?.revision ?: 0} was read; not published." }
                    return@withContext PublishOutcome.Conflicted(write.current)
                }

                is HeadWrite.Written -> check(
                    write.digest == digest
                ) { "The store wrote the head of [$key] as another digest." }
            }
            val published = PublishOutcome.Published(revision, digest)
            try {
                settle(key, RevisionEntry(revision, digest, publishedAt), before - after)
                if (key == SnapshotKey.Platform && base == null) {
                    completeOnNewStore()
                }
            } finally {
                // Exactly once once the head is written, whatever the steps before it threw (B1 review).
                afterWrite(published)
            }
            published
        }
    }

    /**
     * [published], after the head write: a failure is only logged ([publish] says why that is safe). So is a
     * [CancellationException]: this runs without responding to the caller's cancellation, so one that reaches here is a
     * store's own timeout (#1263); the caller's stays pending and takes effect when [publish] returns.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun settle(key: SnapshotKey, entry: RevisionEntry, dropped: Set<Pair<SnapshotKey, PolicyId>>) {
        try {
            published(key, entry, dropped)
        } catch (e: Exception) {
            log.warn(e) {
                "[$key] revision ${entry.revision} is published; its index or the references it dropped could not be " +
                    "updated: history() rebuilds the index, and an extra reference blocks a removal at most."
            }
        }
    }

    /**
     * Marks the app list complete once the platform's first revision is written (RC1-4): on a new store no app document
     * exists before it. Only when the list names every app document [ControlStore.documents] lists that has a head, as
     * `repair-references` leaves it: a platform head that was lost and is published again may find app documents a
     * rebuilt list does not name. A listed app without a head is one whose first publish stopped before its head
     * write; it does not count, since an app lists itself before its head is written. The head is written whatever this
     * does, so a failure is only logged, a store's own timeout among them, as in [settle].
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun completeOnNewStore() {
        try {
            val documents = store.documents()
            repeat(maxAttempts) {
                val current = store.apps()
                val list = current?.value ?: AppList.EMPTY
                val missing = missingApps(documents, list.entries)
                if (missing.isNotEmpty()) {
                    warn(
                        "The platform's first revision found app documents $missing the app list does not name; it " +
                            "stays incomplete until cosec-migrate repair-references runs"
                    )
                    return
                }
                if (list.complete || store.putApps(current?.digest, list.copy(complete = true))) {
                    return
                }
            }
            warn("The app list kept changing and was not marked complete; grants over every app wait for approval")
        } catch (e: Exception) {
            log.warn(e) { "The app list could not be marked complete; grants over every app wait for approval." }
        }
    }

    /**
     * Marks a complete app list incomplete again when [documents] names an app document with a head it does not (RC1-4):
     * one published by an instance or a cosec-migrate that does not keep the list, during a rolling upgrade or after a
     * downgrade. The apps it misses; empty when it misses none, is not complete, or is not there.
     */
    suspend fun reconcileApps(documents: Set<SnapshotKey>): Set<String> {
        var marked: Set<String>? = null
        repeat(maxAttempts) {
            val current = store.apps() ?: return emptySet()
            if (!current.value.complete) {
                // A write whose reply was lost reads back as a conflict: what the earlier attempt found is still so.
                marked?.let { incompleteAgain(it) }
                return marked.orEmpty()
            }
            val missing = missingApps(documents, current.value.entries)
            if (missing.isEmpty()) {
                return emptySet()
            }
            marked = missing
            if (store.putApps(current.digest, current.value.copy(complete = false))) {
                incompleteAgain(missing)
                return missing
            }
        }
        throw IllegalStateException("The app list kept changing; gave up after $maxAttempts attempts.")
    }

    private fun incompleteAgain(missing: Set<String>) {
        warn(
            "The app list misses the app documents $missing; it is incomplete again, and grants over every app wait " +
                "for approval until cosec-migrate repair-references runs"
        )
    }

    /**
     * The apps of [documents] that [listed] does not name and that have a head; one whose head cannot be read counts,
     * since it may well have one.
     */
    private suspend fun missingApps(documents: Set<SnapshotKey>, listed: Set<String>): Set<String> =
        documents.filterIsInstance<SnapshotKey.App>()
            .filter { it.appId !in listed && hasHead(it) }
            .mapTo(HashSet()) { it.appId }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun hasHead(key: SnapshotKey.App): Boolean = try {
        store.head(key) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The head of [$key] could not be read; it counts as an app document the app list misses." }
        true
    }

    /**
     * Lists [apps] among the app documents and marks the list complete (`repair-references`, RC1-4): the caller has
     * listed every app document there is. `false` when the list kept changing.
     */
    suspend fun completeApps(
        apps: Set<String>
    ): Boolean = writes.updateApps { AppList(it.entries + apps, complete = true) }

    /** The document [publish] writes: the candidate's body at the revision after [base], naming [base] as its parent. */
    private fun next(base: Head?, candidate: PolicyDocument, publication: Publication): PolicyDocument = candidate.copy(
        revision = (base?.revision ?: 0) + 1,
        publishedAt = clock.instant(),
        provenance = Provenance(
            parent = base?.let { ParentRevision(it.revision, it.digest.toString()) },
            change = publication.change,
            kind = publication.kind,
            publishedBy = publication.publishedBy,
            approvedBy = publication.approvedBy,
            shadow = publication.shadow,
        ),
    )

    /** After the head moved to [entry] (§5.4 steps 5 and 6): index it, drop the [dropped] references. Failures only warn. */
    private suspend fun published(key: SnapshotKey, entry: RevisionEntry, dropped: Set<Pair<SnapshotKey, PolicyId>>) {
        writes.updateIndex(key) { index ->
            index.copy(
                // Beside any later revision, never over it: a settle that runs late must not cut off the revisions indexed
                // after it. What a head that moved back left above it stays until replaced; history() leaves that out.
                entries = (index.entries.filter { it.revision != entry.revision } + entry).sortedBy { it.revision },
                pending = index.pending.filter { it.digest != entry.digest },
            )
        } || warn("[$key] revision ${entry.revision} is published but not indexed; history() rebuilds it from the chain")
        for ((target, policy) in dropped) {
            writes.updateReferrers(target, policy) { referrerIndex.dropped(it, key, entry.revision) } ||
                warn("[$key] no longer refers to [$policy] of [$target], which stays indexed")
        }
    }

    /**
     * The retained revisions of [key], oldest first, none above its head. When the index is behind the head (a publish
     * that failed after its head write, or a lost index), the missing entries are found along the head's `parent` chain
     * and added.
     */
    suspend fun history(key: SnapshotKey): List<RevisionEntry> {
        // The head as stored: its envelope says its revision, and it is decoded only when the chain has to be walked
        // from it, not for every listing and every explain at a revision (L6 of #1240, up to 8 MB a call).
        val stored = store.head(requireDocumentKey(key)) ?: return store.index(key)?.value?.entries.orEmpty()
        val indexed = store.index(key)?.value ?: RevisionIndex.EMPTY
        // Nothing above the head: what a head that moved back (a partial loss of data) left in the index is not on the
        // chain. A revision published after the head was read is left out of this read alone, and what reclamation
        // removes comes from this list, so it is never taken for one to remove.
        val entries = indexed.entries.filter { it.revision <= stored.document.revision }
        val latest = entries.lastOrNull()?.revision ?: 0
        if (stored.document.revision <= latest) {
            return entries
        }
        val head = Head(key, decode(key, stored.document), stored)
        val missing = ArrayList<RevisionEntry>()
        var document: PolicyDocument? = head.document
        var digest: SnapshotDigest? = head.digest
        while (document != null && digest != null && document.revision > latest) {
            missing += RevisionEntry(document.revision, digest, document.publishedAt)
            val parent = document.provenance?.parent ?: break
            digest = SnapshotDigest.parse(parent.digest)
            document = store.record(key, digest)?.let { decode(key, it) }
        }
        val repaired = missing.sortedBy { it.revision }
        writes.updateIndex(key) { index ->
            val known = index.entries.map { it.revision }.toSet()
            // Against the index as it is at this attempt: reclamation may have taken some out since the chain was walked,
            // and below its floor nothing comes back (#1265).
            val retained = repaired.filter { it.revision >= index.floor }
            index.copy(
                entries = (index.entries + retained.filter { it.revision !in known }).sortedBy { it.revision },
                pending = index.pending.filter { pending -> retained.none { it.digest == pending.digest } },
            )
        } || warn("[$key]: the rebuilt history could not be written back to the index")
        return (entries.filter { it.revision < (repaired.firstOrNull()?.revision ?: Long.MAX_VALUE) } + repaired)
    }

    /**
     * The head [key] had at [revision], found along the `parent` chain from the head, if the records on the way are
     * retained: for a publish retried after its head write, whose own revision may since have been replaced, and which
     * the index may not hold (review item 13).
     */
    internal suspend fun at(key: SnapshotKey, revision: Long): Head? {
        var current = head(key) ?: return null
        while (current.revision > revision) {
            val parent = current.document.provenance?.parent ?: return null
            val digest = SnapshotDigest.parse(parent.digest)
            val record = store.record(key, digest) ?: return null
            current = Head(key, decode(key, record), StoredDocument(record, digest))
        }
        return current.takeIf { it.revision == revision }
    }

    /**
     * What follows the head write of [published], for a publish retried because its reply was lost (review item 13):
     * its index entry, added where it is missing and never replacing a later one, and the app list's completion on a
     * new store when [first]. The references it dropped stay indexed: a later revision may refer to them again, and a
     * superset blocks a deletion at most. Failures only warn, as after the write.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun rewritten(key: SnapshotKey, published: Head, first: Boolean) = withContext(NonCancellable) {
        val entry = RevisionEntry(published.revision, published.digest, published.document.publishedAt)
        try {
            writes.updateIndex(key) { index ->
                index.copy(
                    entries = (index.entries.filter { it.revision != entry.revision } + entry).sortedBy { it.revision },
                    pending = index.pending.filter { it.digest != entry.digest },
                )
            } || warn("[$key] revision ${entry.revision} is published but not indexed; history() rebuilds it")
        } catch (e: Exception) {
            log.warn(e) { "[$key] revision ${entry.revision} is published; its index entry could not be written." }
        }
        if (key == SnapshotKey.Platform && first) {
            completeOnNewStore()
        }
    }

    /** The document [key] had at [revision], if its record is retained: for rollback and for explaining by version. */
    suspend fun revision(key: SnapshotKey, revision: Long): PolicyDocument? {
        val entry = history(key).firstOrNull { it.revision == revision } ?: return null
        return store.record(key, entry.digest)?.let { decode(key, it) }
    }

    /**
     * The body to roll [key] back to [revision] (RFC 0007 §5.6): the policies, bindings and catalog of that revision.
     * It is published like any change, with [ProvenanceKind.ROLLBACK], and goes through every check of the current
     * documents; only the whole document is restored, never one change undone.
     */
    suspend fun rollbackCandidate(key: SnapshotKey, revision: Long): PolicyDocument =
        revision(key, revision) ?: throw IllegalArgumentException("[$key] has no retained revision $revision.")

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

    /**
     * [document], a stored one of [key], decoded. One that does not decode (a newer version's, during a rolling
     * upgrade) names its key, so that the control API logs which one: its answer is a 500 without detail (RC1-39).
     */
    internal fun decode(key: SnapshotKey, document: SnapshotDocument): PolicyDocument {
        val (kind, owner) = ownerOf(key)
        return try {
            PolicyDocumentCodec.decode(kind, owner, document.bodyBytes())
        } catch (e: SnapshotFormatException) {
            throw SnapshotFormatException(
                "The stored document [$key] revision ${document.revision} does not decode: ${e.message}",
                e
            )
        }
    }

    /** The head of [key] as stored, not decoded: its digest names its content (RC1-41). */
    internal suspend fun stored(key: SnapshotKey): StoredDocument? = store.head(requireDocumentKey(key))

    /** The digest [key] had at [revision], if its record is retained: what [revision] reads, before reading it. */
    internal suspend fun retainedDigest(key: SnapshotKey, revision: Long): SnapshotDigest? =
        history(key).firstOrNull { it.revision == revision }?.digest

    /** The record of [key] with [digest], if retained. */
    internal suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument? = store.record(key, digest)

    /** [ReferrerIndex.referrers]: the documents indexed as referring to [policy] of [target] (RFC 0007 §5.8). */
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> = referrerIndex.referrers(
        target,
        policy
    )

    /** [ReferrerIndex.repairReferences]: adds what the head of [key] refers to and the index misses. */
    suspend fun repairReferences(key: SnapshotKey): Int = referrerIndex.repairReferences(key)

    /** [ReferrerIndex.pruneReferences]: removes the references to [target]'s policies that no head has. */
    suspend fun pruneReferences(target: SnapshotKey, heads: ReferrerHeads = ReferrerHeads()): Int =
        referrerIndex.pruneReferences(target, heads)

    @InternalCoSecApi
    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 16

        private fun requireDocumentKey(key: SnapshotKey): SnapshotKey = key.also { ownerOf(it) }
    }
}
