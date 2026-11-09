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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.shadow.CandidateStore
import me.ahoo.cosec.runtime.shadow.StoredCandidate
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
@CoSecSpi
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
     * The documents that refer to [policy] of [target] (RFC 0007 §5.8), each with the revision of it that last indexed
     * the reference, and the digest a [putReferrers] must name. A revision removes only what it or an earlier one
     * indexed, so the late removal of one revision never takes back what a later one indexed again (#1207).
     */
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Versioned<Map<SnapshotKey, Long>>?

    /** Replaces the referrers of [policy] of [target] if they are still at [expected]; `false` when they changed. */
    suspend fun putReferrers(
        target: SnapshotKey,
        policy: PolicyId,
        expected: SnapshotDigest?,
        referrers: Map<SnapshotKey, Long>,
    ): Boolean

    /** The change request [id], with the digest a [putChange] must name, or `null` when there is none. */
    suspend fun change(id: ChangeId): Versioned<ChangeRequest>?

    /**
     * Writes the change request if the store still holds [expected] (`null`: only if there is none): every state
     * transition is one such write (RFC 0007 §6.1). `false` when it changed in between.
     */
    suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean

    /** The staging slot of [key] (RFC 0007 §5.5), with the digest a [putStage] must name; `null` when never taken. */
    suspend fun stage(key: SnapshotKey): Versioned<StageSlot>?

    /** Replaces the staging slot of [key] if it is still at [expected]; `false` when it changed in between. */
    suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean

    /**
     * Writes the candidate of [key] to the `candidate:` namespace the data plane's shadow listeners read (RFC 0007 §6.4).
     * Unconditional: the staging slot already makes its holder the only writer.
     */
    suspend fun putCandidate(key: SnapshotKey, candidate: StoredCandidate)

    /**
     * Removes the candidate of [key] if it is still the one of [window], in one atomic step (#1227): a change withdraws
     * its candidate once it is terminal, when another change may already hold the slot and have staged its own, which
     * must stay. `true` when this removed it; `false` when the candidate is another window's or there is none. A
     * removal whose outcome is unknown is resolved by reading back, never by removing unconditionally.
     */
    suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean

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
     * The change requests that may still be open, so shadow windows can be closed (RFC 0007 §6.4) and the revisions
     * open changes rest on kept (§5.7). It may name more, never fewer: an id is added before its change is first written
     * and removed once a write makes the change terminal. A removal that failed is made up here: ids whose change is
     * terminal are dropped when this is read, and so are ids whose change has been missing for longer than a write can
     * take (a first write that failed). Callers still read each change and go by its state. A listed change whose
     * record does not decode fails this, as [change] does: it cannot be known to be terminal.
     */
    suspend fun openChanges(): Set<ChangeId>

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
class StoredDocument(val document: SnapshotDocument, val digest: SnapshotDigest) {
    override fun toString(): String = "StoredDocument(revision=${document.revision}, digest=$digest)"
}

/** A value with the digest it is stored under, for a conditional replace. */
class Versioned<T>(val value: T, val digest: SnapshotDigest)

/** The outcome of [ControlStore.putHead]. */
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
data class AppList(val entries: Set<String>, val complete: Boolean) {
    companion object {
        val EMPTY: AppList = AppList(emptySet(), complete = false)
    }
}

/** One published revision of a document. */
data class RevisionEntry(val revision: Long, val digest: SnapshotDigest, val publishedAt: Instant)

/** A revision record written for a publish whose head write has not been confirmed (RFC 0007 §5.4 step 2). */
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
data class RevisionIndex(
    val entries: List<RevisionEntry> = emptyList(),
    val pending: List<PendingRecord> = emptyList(),
    val floor: Long = 1,
    val reclaiming: List<SnapshotDigest> = emptyList(),
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
