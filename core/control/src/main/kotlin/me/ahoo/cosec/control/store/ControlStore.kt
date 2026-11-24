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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.time.Instant

/**
 * Where the control plane keeps the documents it publishes and what it needs to publish them safely (RFC 0007 §5.2):
 * the heads the data plane reads, the revision records they are built from, the revision index of each document, the
 * back-reference index of cross-document references, the change requests, each document's staging slot and the candidate it stages. `CoCacheControlStore` in `cosec-cocache` is the Redis
 * implementation; [InMemoryControlStore] is for tests and single-instance development.
 *
 * Every write is conditional on the digest it was based on: nothing is overwritten that the caller has not read. Reads
 * go to the store of record, never to a local copy, since a decision of the control plane must not rest on a copy that
 * may lag (RFC 0007 §7.2). A read that fails throws: reading it as absent would publish on the wrong base.
 *
 * Digests are the store's own ([digestOf]); the head's digest is also the address of its revision record, which is
 * what a document's `provenance.parent` points to (RFC 0007 §5.3).
 */
// stays: one method per store operation of ControlStore
@Suppress("TooManyFunctions")
@CoSecSpi
@ExperimentalCoSecApi
interface ControlStore : CandidateStore {
    /** The digest this store computes for [document]: the same for the head and for its revision record. */
    fun digestOf(document: SnapshotDocument): SnapshotDigest

    /** The head of [key] as the store holds it, or `null` when the document does not exist. */
    suspend fun head(key: SnapshotKey): StoredDocument?

    /**
     * Replaces the head of [key] with [document] if the store still holds [expected] (`null`: only if there is none),
     * in one atomic step. A write whose outcome is unknown (it may have been applied) is resolved by reading the
     * digest back, never by repeating it: the result is [HeadWrite.Written] only if [document] is the head.
     */
    suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite

    /** The revision record of [key] at [digest], or `null` when there is none. */
    suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument?

    /**
     * Writes the revision record of [document], create-only: records are addressed by their digest, so an existing
     * record at the same address holds the same bytes and the write succeeds without changing it.
     */
    suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest

    /** Removes the revision record of [key] at [digest]; reclamation only (RFC 0007 §5.7). */
    suspend fun removeRecord(key: SnapshotKey, digest: SnapshotDigest)

    /** The revision index of [key], with the digest a [putIndex] must name, or `null` when there is none. */
    suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>?

    /** Replaces the revision index of [key] if it is still at [expected]; `false` when it changed in between. */
    suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean

    /**
     * The references to [policy] of [target] (RFC 0007 §5.8): one [ReferrerEntry] per document and revision that indexed
     * one, empty when there is none. A value the store cannot read, a stored format older than this one included,
     * throws: it is never read as no referrers (`CTL-42`), which would let the policy be removed.
     */
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry>

    /**
     * Adds [entry] to the references to [policy] of [target]; adding one that is there changes nothing. Each entry is a
     * member of its own, so two documents, or two revisions of one, never write over each other. Throws when the value
     * cannot be read or written, never answering as if it had added.
     */
    suspend fun addReferrer(target: SnapshotKey, policy: PolicyId, entry: ReferrerEntry)

    /**
     * Removes exactly [entries] from the references to [policy] of [target], and nothing else: an entry another revision
     * added meanwhile is another member and stays (#1207, `CTL-25`). Throws when the value cannot be read or written.
     */
    suspend fun removeReferrers(target: SnapshotKey, policy: PolicyId, entries: Set<ReferrerEntry>)

    /** The change request [id], with the digest a [putChange] must name, or `null` when there is none. */
    suspend fun change(id: ChangeId): Versioned<ChangeRequest>?

    /**
     * Writes the change request if the store still holds [expected] (`null`: only if there is none): every state
     * transition is one such write (RFC 0007 §6.1). `false` when it changed in between. [previous] is the state of the
     * record [expected] names (`null` with no record): a store that lists changes by state takes that record out of
     * that state's listing alone (`CTL-54`), so a caller that leaves it out costs that store a stale member, which it
     * reads and drops later, never a wrong listing. A write never repeats an earlier record of the change: the
     * lifecycle writes each one later (`updatedAt`) than the record it replaces.
     */
    suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest, previous: ChangeState? = null): Boolean

    /**
     * Deletes the record of the ended change [id] of [document] if the store still holds [expected], and takes [id] out
     * of [document]'s ended changes ([endedChanges]); with [expected] `null`, only where there is no record, which takes
     * out an entry whose record is gone. `false` when the store holds another record. The control plane removes only a
     * terminal change, one reclamation or the bound on a document's ended changes lets go (`CTL-83`).
     */
    suspend fun removeChange(document: SnapshotKey, id: ChangeId, expected: SnapshotDigest?): Boolean

    /**
     * The changes of [document] that ended before [endedBefore], the oldest first, at most [limit] (`CTL-83`): a
     * [putChange] of a terminal change lists it, under its `updatedAt`, before it writes it, so every change that ended
     * since the store kept the list is among them, until [removeChange] takes it out. A listed change may not be
     * terminal (its write lost) or may be gone: whoever acts on one reads its record first. A change that ended before
     * the store kept the list is not listed.
     */
    suspend fun endedChanges(document: SnapshotKey, endedBefore: Instant, limit: Int): List<EndedChange>

    /** The staging slot of [key] (RFC 0007 §5.5), with the digest a [putStage] must name; `null` when never taken. */
    suspend fun stage(key: SnapshotKey): Versioned<StageSlot>?

    /** Replaces the staging slot of [key] if it is still at [expected]; `false` when it changed in between. */
    suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean

    /** The candidate of [key] with the digest a [putCandidate] must name, or `null` when there is none. */
    suspend fun stagedCandidate(key: SnapshotKey): Versioned<StoredCandidate>?

    /**
     * Writes the candidate of [key] to the `candidate:` namespace the data plane's shadow listeners read (RFC 0007 §6.4)
     * if it is still at [expected] (`null`: only if there is none); `false` when it changed in between. The staging slot
     * does not make its holder the only writer: a change that held it when it read the candidate may be preempted
     * before its write lands, and its late write must not replace the candidate of the change that took the slot (#1235).
     * A write whose outcome is unknown is resolved by reading back, never by writing again.
     */
    suspend fun putCandidate(key: SnapshotKey, expected: SnapshotDigest?, candidate: StoredCandidate): Boolean

    /**
     * Removes the candidate of [key] if it is still the one of [window], in one atomic step (#1227): a change withdraws
     * its candidate once it is terminal, when another change may already hold the slot and have staged its own, which
     * must stay. `true` when this removed it; `false` when the candidate is another window's or there is none. A
     * removal whose outcome is unknown is resolved by reading back, never by removing unconditionally.
     */
    suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean

    /**
     * Lists [key]'s candidate of [window] in the staged list (`CTL-46`), before that candidate is written: one member,
     * added unconditionally, so stages of other documents at the same moment never make it fail. Adding a member that
     * is there changes nothing. Throws when it cannot be written: the stage fails then, and nothing is staged. A store
     * that keeps no list does nothing ([staged] is null).
     */
    suspend fun listStaged(key: SnapshotKey, window: String) = Unit

    /**
     * Removes the member of [key]'s candidate of [window] from the staged list, after that candidate was withdrawn
     * (`CTL-46`); any other member stays. Throws when it cannot be written; the shadow pass removes it later.
     */
    suspend fun unlistStaged(key: SnapshotKey, window: String) = Unit

    /**
     * Makes the staged list kept, when it is not: from then on [staged] answers a list, empty when nothing is staged,
     * and never null. The control plane does it at startup and in every shadow pass; an absent list means one nobody
     * keeps, and readers fall back to reading candidates by key (`CTL-47`). A downgrade to a control plane that keeps
     * none must delete it.
     */
    suspend fun keepStagedList() = Unit

    /**
     * Every document this store has indexed revisions for, so reclamation can visit each (RFC 0007 §5.7). It may name
     * more than exist, never fewer: a key is added before its first index write and never removed.
     */
    suspend fun documents(): Set<SnapshotKey>

    /**
     * The app documents there are (RC1-4), with the digest a [putApps] must name; `null` when there is none. Small, apart
     * from [documents], so the grant analysis reads it on every tenant change; [Revisions] keeps it.
     */
    suspend fun apps(): Versioned<AppList>?

    /** Replaces the app list if it is still at [expected] (`null`: only if there is none); `false` when it changed. */
    suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean

    /**
     * Whether the open-change sets can be trusted (R2C-2, `CTL-54`): a new store's, or a converted one's, kept and with no
     * list of the format before 8.0.0-beta.5 left. While that list exists, an open change only it names may be missing
     * from the sets, so reclamation reclaims nothing and the listing is refused until `cosec-migrate repair-references`
     * converts it; an older control plane that writes the old list again puts the store back to not kept.
     */
    suspend fun openChangesKept(): Boolean

    /**
     * Marks the open-change sets kept, but only where no list of the format before exists (`CTL-54`): the control plane
     * does it at startup and in its maintenance passes, so a new store is kept from its start. The conversion keeps a
     * converted store itself, after it added every listed change.
     */
    suspend fun keepOpenChanges()

    /**
     * The changes of [document] that are not terminal, each read now (RFC 0007 §6.4, §5.7): every written change of it
     * that is not terminal is among them. A change is listed, in its document's set and in its state's, before each
     * write, and unlisted from what no longer applies after it, so the sets are a superset (`CTL-54`): a member whose
     * change is terminal or missing is skipped, and one whose record does not read is [ListedChange.Unreadable], never
     * dropped.
     */
    suspend fun openChanges(document: SnapshotKey): List<ListedChange>

    /**
     * The changes listed in [state] of the documents [documents] accepts, ordered by document and id, after the cursor
     * [after] (`null`: from the first), at most [limit] of them, each read now and still in [state] (a member a later
     * write moved on is skipped). A member of a document [documents] refuses is neither read nor counted. [ChangePage.next]
     * is the cursor of the next page, the last change of this one; `null` after the last.
     */
    suspend fun changes(
        state: ChangeState,
        after: String?,
        limit: Int,
        documents: (SnapshotKey) -> Boolean = { true },
    ): ChangePage

    /**
     * Takes the lease [name] for [holder] for [duration] if nobody holds it, so that one control-plane instance runs a
     * periodic task (RFC 0007 §5.7). `false` when another holder has it. [holder] taking a lease it already has renews it:
     * it then holds it for the full [duration] from now, not for what was left of the earlier one.
     */
    suspend fun acquireLease(name: String, holder: String, duration: Duration): Boolean

    /**
     * Gives up the lease [name] if [holder] still has it, so the next taker need not wait for it to expire. The check and
     * the removal are one atomic step: a lease that expired meanwhile and that another holder took stays theirs, or two
     * instances would run the same task at once.
     */
    suspend fun releaseLease(name: String, holder: String)
}

/** A stored document and the store's digest of it. */
@ExperimentalCoSecApi
class StoredDocument(val document: SnapshotDocument, val digest: SnapshotDigest) {
    override fun toString(): String = "StoredDocument(revision=${document.revision}, digest=$digest)"
}

/** A value with the digest it is stored under, for a conditional replace. */
@ExperimentalCoSecApi
class Versioned<T>(val value: T, val digest: SnapshotDigest)

/** The outcome of [ControlStore.putHead]. */
@ExperimentalCoSecApi
sealed interface HeadWrite {
    class Written(val digest: SnapshotDigest) : HeadWrite

    /** The head was not [expected][ControlStore.putHead]; [current] is what the store holds, `null` for nothing. */
    class Conflict(val current: SnapshotDigest?) : HeadWrite
}

/**
 * The app documents there are (RC1-4). [entries] are app ids as they were listed, kept as written even when one does not
 * read as an app id, so nothing is ever dropped from the list. [complete] says that every app document is among them:
 * true once the platform document's first revision is written and the list names every app document with a head that
 * [ControlStore.documents] lists, or once `cosec-migrate repair-references` has listed every app document
 * [ControlStore.documents] lists. A list rebuilt after it was lost starts incomplete, and the reclamation pass marks a
 * complete one incomplete again when [ControlStore.documents] lists an app document with a head it misses. The grant
 * analysis trusts only a complete list whose every entry reads as an app id.
 */
@ExperimentalCoSecApi
data class AppList(val entries: Set<String>, val complete: Boolean) {
    companion object {
        val EMPTY: AppList = AppList(emptySet(), complete = false)
    }
}

/**
 * One published revision of a document: its record [digest], and [parent], the digest its document names as its parent
 * (`null` for the first revision, and for an entry an older version wrote). An entry is a fact about an immutable
 * record, so whoever writes the entry of a digest writes the same [parent]; a list of entries is the head's chain when
 * each entry's [parent] is the digest of the one below it ([Revisions.history]).
 */
@ExperimentalCoSecApi
data class RevisionEntry(
    val revision: Long,
    val digest: SnapshotDigest,
    val publishedAt: Instant,
    val parent: SnapshotDigest? = null,
    /**
     * The stored bytes of its record (`CTL-84`); `null` for an entry an older version wrote, whose record reclamation
     * reads once to learn it.
     */
    val size: Long? = null,
)

/** A revision record written for a publish whose head write has not been confirmed (RFC 0007 §5.4 step 2). */
@ExperimentalCoSecApi
data class PendingRecord(val digest: SnapshotDigest, val since: Instant)

/**
 * The revisions of one document (RFC 0007 §5.2): what the head's `parent` chain says, kept so history and rollback do
 * not walk the chain. It can be lost, and is then rebuilt from the chain ([Revisions.history]).
 *
 * - [entries], by ascending revision, are the published revisions whose records are retained;
 * - [pending] are records written before their head write; once the head moves they become entries, and those whose
 *   publish lost its race are orphans that reclamation removes;
 * - [floor] is the lowest revision whose record may still exist: below it, records were reclaimed on purpose, so a
 *   chain that ends there is complete, not broken;
 * - [reclaiming] are records reclamation has taken out of [entries] or [pending] and not yet seen deleted (#1226): it
 *   writes them here in the same step, then deletes them, then drops the ones it deleted, so a deletion that fails is
 *   tried again by the next pass, and no listed revision ever misses its record.
 */
@ExperimentalCoSecApi
data class RevisionIndex(
    val entries: List<RevisionEntry> = emptyList(),
    val pending: List<PendingRecord> = emptyList(),
    val floor: Long = 1,
    val reclaiming: List<SnapshotDigest> = emptyList(),
    /**
     * What this document indexed in the back-reference index and has not yet seen dropped: the exact entries a later
     * revision removes by, without reading the references of others (`CTL-44`). Written before the entries are added,
     * so a crash leaves a record of an entry that is not there, which its removal skips, never an entry nobody knows.
     */
    val indexed: List<IndexedReference> = emptyList(),
) {
    init {
        require(
            entries.zipWithNext().all { (a, b) -> a.revision < b.revision }
        ) { "Entries are by ascending revision." }
        require(floor >= 1) { "The floor is a revision, at least 1." }
    }

    val latest: RevisionEntry? get() = entries.lastOrNull()

    companion object {
        val EMPTY: RevisionIndex = RevisionIndex()
    }
}

/** A change an open-change listing names: as read now, or one whose stored record does not read. */
@ExperimentalCoSecApi
sealed interface ListedChange {
    val id: ChangeId
    val document: SnapshotKey

    class Readable(val change: ChangeRequest) : ListedChange {
        override val id: ChangeId get() = change.id
        override val document: SnapshotKey get() = change.key
    }

    /** Listed, and its record does not read: shown as such, never left out (`CTL-53`). */
    class Unreadable(override val id: ChangeId, override val document: SnapshotKey) : ListedChange
}

/** One page of [ControlStore.changes]: its changes, and the cursor of the next page, `null` after the last. */
@ExperimentalCoSecApi
class ChangePage(val changes: List<ListedChange>, val next: String?)

/** A change [ControlStore.endedChanges] lists: its [id], and [endedAt], the `updatedAt` of its terminal write. */
@ExperimentalCoSecApi
class EndedChange(val id: ChangeId, val endedAt: Instant) {
    override fun equals(other: Any?): Boolean = other is EndedChange && other.id == id && other.endedAt == endedAt

    override fun hashCode(): Int = 31 * id.hashCode() + endedAt.hashCode()

    override fun toString(): String = "EndedChange(id=${id.value}, endedAt=$endedAt)"
}
