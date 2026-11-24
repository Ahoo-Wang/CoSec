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
// stays: the face its callers use; ten of its functions are one-line delegations to the part that does the work
@Suppress("TooManyFunctions")
@InternalCoSecApi
class Revisions(
    private val store: ControlStore,
    /** The control plane's clock: what its revisions are stamped with, and what its shared reads are aged by. */
    internal val clock: Clock = Clock.systemUTC(),
    val retention: RetentionPolicy = RetentionPolicy(),
    /** Attempts of a conditional replace of the index or the back-references before it gives up. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val writes = RevisionWrites(store, maxAttempts)
    private val referrerIndex = ReferrerIndex(store, writes, ::head)
    private val listing = AppListing(store, writes, maxAttempts)
    internal val repair = HistoryRepair(store, writes, ::decode)
    private val retained = Retention(store, writes, clock, retention, ::head, ::decode, ::historyAndHead)

    /** Records this instance reclaimed younger than `min-age`, past what a document retains (`CTL-84`). */
    val reclaimedYoung: Long get() = retained.budget.reclaimedYoung.get()

    /** Every document the store indexes revisions for ([ControlStore.documents]): it may name more than have a head. */
    suspend fun documents(): Set<SnapshotKey> = store.documents()

    /** Whether the store indexes revisions for [key]: one read of its index, never the whole list ([documents]). */
    suspend fun indexed(key: SnapshotKey): Boolean = store.index(requireDocumentKey(key)) != null

    /** The head of [key], read from the store of record. */
    suspend fun head(key: SnapshotKey): Head? =
        store.head(requireDocumentKey(key))?.let { Head(key, decode(key, it.document), it) }

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
        val before = base?.let { referrerIndex.references(it.document) }.orEmpty()
        val after = referrerIndex.references(document)
        val added = after - before
        // One write: the record pending, and what the revision indexes (its forward record, CTL-44), before either.
        writes.updateIndex(key) { index ->
            index.copy(
                pending = index.pending + PendingRecord(digest, publishedAt),
                indexed = index.indexed + added.map { it.at(revision) },
            )
        } || warn("[$key] revision $revision: its record could not be registered as pending; an orphan would stay")
        check(store.putRecord(key, stored) == digest) { "The store addressed the record of [$key] by another digest." }
        referrerIndex.addEntries(key, revision, added)
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
                settle(key, entryOf(document, digest, stored.bodyBytes().size.toLong()), after)
                if (key == SnapshotKey.Platform && base == null) {
                    listing.completeOnNewStore()
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
    private suspend fun settle(key: SnapshotKey, entry: RevisionEntry, kept: Set<ReferrerIndex.Reference>) {
        try {
            published(key, entry, kept)
        } catch (e: Exception) {
            log.warn(e) {
                "[$key] revision ${entry.revision} is published; its index or the references it dropped could not be " +
                    "updated: history() rebuilds the index, and an extra reference blocks a removal at most."
            }
        }
    }

    /** The index entry of [document], the revision whose record has [digest] and [size] bytes: with the parent it names. */
    private fun entryOf(document: PolicyDocument, digest: SnapshotDigest, size: Long): RevisionEntry {
        val parent = document.provenance?.parent?.digest?.let(SnapshotDigest::parse)
        return RevisionEntry(document.revision, digest, document.publishedAt, parent, size)
    }

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

    /**
     * After the head moved to [entry] (§5.4 steps 5 and 6): index it, then drop the references its record holds that the
     * published revision no longer has ([kept] is what it has). Failures only warn.
     */
    private suspend fun published(key: SnapshotKey, entry: RevisionEntry, kept: Set<ReferrerIndex.Reference>) {
        if (!index(key, entry)) {
            warn("[$key] revision ${entry.revision} is published but not indexed; history() rebuilds it from the chain")
        }
        referrerIndex.dropGone(key, entry.revision, kept)
    }

    /**
     * The retained revisions of [key], oldest first, none above its head, and each the head's own: when the index is
     * behind the head (a publish that failed after its head write, or a lost index) or holds another branch's entry
     * of a number the head reused, the chain is walked from the head and the index follows it ([HistoryRepair]).
     */
    suspend fun history(key: SnapshotKey): List<RevisionEntry> = historyAndHead(key).first

    /** [history], and the digest of the head it was read against (null when there is none): one read of the head. */
    internal suspend fun historyAndHead(key: SnapshotKey): Pair<List<RevisionEntry>, SnapshotDigest?> {
        // The head as stored: its envelope says its revision, and it is decoded only when the chain has to be walked
        // from it, not for every listing and every explain at a revision (L6 of #1240, up to 8 MB a call).
        val stored = store.head(requireDocumentKey(key)) ?: return store.index(key)?.value?.entries.orEmpty() to null
        val indexed = store.index(key)?.value ?: RevisionIndex.EMPTY
        // What reclamation removes comes from this list, so a revision published after the head was read is never taken
        // for one to remove.
        val entries = HistoryRepair.entriesUpTo(indexed, stored)
        // The head is the authority (#1536): an index whose last entry is not the head's, by digest, is behind it or on
        // another branch, and is rebuilt from the chain.
        val history = if (HistoryRepair.agrees(entries, indexed.floor, stored)) {
            entries
        } else {
            repair.rebuild(key, stored, indexed, entries)
        }
        return history to stored.digest
    }

    /**
     * Adds [entry] to the index beside any later revision, never over it: a settle that runs late must not cut off the
     * revisions indexed after it. A number that is listed with another digest stays as it is (#1536): which of two
     * records is the head's chain's is for [history] to say, by walking from the head, and a settle of another branch
     * must not put its entry over a right one. The same digest is written again, which gives an entry an older version
     * wrote its `parent`. `false` when the index could not be written.
     */
    private suspend fun index(key: SnapshotKey, entry: RevisionEntry): Boolean = writes.updateIndex(key) { index ->
        val listed = index.entries.firstOrNull { it.revision == entry.revision }
        index.copy(
            entries = if (listed == null || listed.digest == entry.digest) {
                (index.entries.filter { it.revision != entry.revision } + entry).sortedBy { it.revision }
            } else {
                index.entries
            },
            pending = index.pending.filter { it.digest != entry.digest },
        )
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
        val entry = entryOf(published.document, published.digest, published.stored.document.bodyBytes().size.toLong())
        try {
            index(key, entry) ||
                warn("[$key] revision ${entry.revision} is published but not indexed; history() rebuilds it")
        } catch (e: Exception) {
            log.warn(e) { "[$key] revision ${entry.revision} is published; its index entry could not be written." }
        }
        if (key == SnapshotKey.Platform && first) {
            listing.completeOnNewStore()
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

    /** The entries of the references to [policy] of [target] (RFC 0007 §5.8): who refers, at which revision, how. */
    suspend fun referrerEntries(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry> =
        referrerIndex.entries(target, policy)

    /** [ReferrerIndex.referrers]: the documents indexed as referring to [policy] of [target] (RFC 0007 §5.8). */
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> = referrerIndex.referrers(
        target,
        policy
    )

    /** Whether the head of [referrer] is a tenant's that defines [policy] itself (RC1-9): its entries say `#own`. */
    suspend fun definesItself(referrer: SnapshotKey, policy: PolicyId): Boolean = referrerIndex.definesItself(
        referrer,
        policy
    )

    /** [ReferrerIndex.referenceKeys]: the back-reference sets the head of [key] touches. */
    suspend fun referenceKeys(key: SnapshotKey): Set<Pair<SnapshotKey, PolicyId>> = referrerIndex.referenceKeys(key)

    /** [ReferrerIndex.repairReferences]: adds what the head of [key] refers to and the index misses. */
    suspend fun repairReferences(key: SnapshotKey): Int = referrerIndex.repairReferences(key)

    /** [ReferrerIndex.pruneReferences]: removes the references to [target]'s policies that no head has. */
    suspend fun pruneReferences(target: SnapshotKey, heads: ReferrerHeads = ReferrerHeads()): Int =
        referrerIndex.pruneReferences(target, heads)

    /** [AppListing.reconcileApps]: marks a complete app list incomplete when [documents] names an app it misses. */
    suspend fun reconcileApps(documents: Set<SnapshotKey>): Set<String> = listing.reconcileApps(documents)

    /** [AppListing.completeApps]: lists [apps] and marks the app list complete. */
    suspend fun completeApps(apps: Set<String>): Boolean = listing.completeApps(apps)

    /** [Retention.verifyChain]: walks the `parent` chain of [key]'s head. */
    suspend fun verifyChain(key: SnapshotKey): ChainCheck = retained.verifyChain(key)

    /**
     * Whether the revision index of [key] follows its head's chain (`CTL-35`), as [history] checks it before it
     * returns the index, but only read: nothing is rebuilt, so `verify-chain` may run beside the control plane. An index one
     * revision behind the head is a publication settling, or one whose index write failed: [IndexCheck.Behind], not a
     * difference (as a reclamation during a walk is not a lost write, #1532).
     */
    suspend fun verifyIndex(key: SnapshotKey): IndexCheck {
        val stored = store.head(requireDocumentKey(key)) ?: return IndexCheck.Follows
        val indexed = store.index(key)?.value ?: RevisionIndex.EMPTY
        val entries = HistoryRepair.entriesUpTo(indexed, stored)
        // The head is decoded once, and only to tell a publication that is settling from a difference.
        return HistoryRepair.check(entries, indexed.floor, stored) {
            decode(key, stored.document).provenance?.parent?.digest?.let(SnapshotDigest::parse)
        }
    }

    /** [Retention.reclaim]: removes the records of [key] the [retention] no longer keeps. */
    suspend fun reclaim(key: SnapshotKey, keep: Set<SnapshotDigest> = emptySet()): Reclaimed = retained.reclaim(
        key,
        keep
    )

    @InternalCoSecApi
    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 16

        private fun requireDocumentKey(key: SnapshotKey): SnapshotKey = key.also { ownerOf(it) }
    }
}
