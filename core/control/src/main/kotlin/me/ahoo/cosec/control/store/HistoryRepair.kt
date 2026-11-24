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
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the revision index the head's chain (#1536). The head is the authority; an index entry is a fact about an
 * immutable record (its [RevisionEntry.parent] is the one its document names), so a list of entries is the head's chain
 * exactly when its last entry is the head's and each entry's parent is the digest of the entry below it. That is
 * checked at every read ([agrees]) from the entries alone, whatever order the writers settled in: no record is read.
 * When it does not hold the index is behind the head or holds another branch, and [rebuild] walks the head's records.
 */
internal class HistoryRepair(
    private val store: ControlStore,
    private val writes: RevisionWrites,
    /** A stored document decoded, as [Revisions.decode] decodes it. */
    private val decode: (SnapshotKey, SnapshotDocument) -> PolicyDocument,
) {
    /** The record a walk of a document found missing, last logged: one digest per document, so a read logs it once. */
    private val reported = ConcurrentHashMap<SnapshotKey, SnapshotDigest>()

    /** What a walk from the head found: the chain down to where the index is right, and where it could not go on. */
    private class Walk(
        /** The chain's entries, oldest first, that the index does not have right. */
        val found: List<RevisionEntry>,
        /** The revision of the sound entry the walk met and stopped at, if it did. */
        val anchor: Long?,
        /** The digest and revision whose record is missing, if that is where the walk ended. */
        val missing: Pair<Long, SnapshotDigest>?,
    )

    /**
     * [indexed], rebuilt from the chain of [stored], the head of [key]; [entries] are its entries up to the head.
     *
     * The walk stops at a revision whose index entry is the chain's *and* lies in the sound prefix of the index (the
     * entries whose links hold from the lowest one up): the rest is then the chain's. What differs is replaced in the
     * index CAS, and only where the index at the write still has the entry the walk compared, so another repair or
     * a republication of that number is never overwritten. Nothing below the floor of the attempt is written
     * (#1265). Digests the repair replaces go to [RevisionIndex.reclaiming], where the next reclamation deletes them
     * unless something still holds them; a digest it lists leaves it: [RevisionIndex.reclaiming] never holds a
     * digest the history lists.
     *
     * A walk that ends at a missing record without meeting a sound entry returns only what it found: the entries
     * below cannot be shown to be on the chain, so they are not returned, and stay in the index as they are.
     */
    suspend fun rebuild(
        key: SnapshotKey,
        stored: StoredDocument,
        indexed: RevisionIndex,
        entries: List<RevisionEntry>,
    ): List<RevisionEntry> {
        // Below the floor are the revisions reclamation kept for an open change: listed when they were on the chain, and
        // with nothing above them to link to (what lay between was reclaimed), so they are not judged again.
        val kept = entries.filter { it.revision < indexed.floor }
        val live = entries.filter { it.revision >= indexed.floor }
        val sound = live.take(soundPrefix(live))
        val compared = live.associateBy { it.revision }
        val walk = walk(key, stored, indexed.floor, compared, sound.mapTo(HashSet()) { it.revision })
        var applied: List<RevisionEntry> = emptyList()
        var dropped: List<RevisionEntry> = emptyList()
        val written = writes.updateIndex(key) { index ->
            applied = walk.found.filter { entry ->
                entry.revision >= index.floor &&
                    index.entries.firstOrNull { it.revision == entry.revision }?.digest ==
                    compared[entry.revision]?.digest
            }
            dropped = applied.mapNotNull { compared[it.revision]?.takeIf { old -> old.digest != applied.digestAt(old) } }
            val revisions = applied.map { it.revision }.toSet()
            index.copy(
                entries = (index.entries.filter { it.revision !in revisions } + applied).sortedBy { it.revision },
                pending = index.pending.filter { pending -> applied.none { it.digest == pending.digest } },
                reclaiming = (
                    index.reclaiming.filter { listed -> applied.none { it.digest == listed } } +
                        dropped.map { it.digest }
                    ).distinct(),
            )
        }
        if (!written) {
            warn("[$key]: the rebuilt history could not be written back to the index")
        }
        if (written && dropped.isNotEmpty()) {
            log.warn {
                "[$key]: the head's chain differs from the revision index (a head that moved back and a revision " +
                    "number reused, or a partly restored store); the index now follows the head. Dropped, kept: " +
                    dropped.joinToString("; ") { old ->
                        "revision ${old.revision} ${old.digest}, ${applied.digestAt(old)}"
                    } + ". The dropped records will be reclaimed."
            }
        }
        walk.missing?.let { (revision, digest) -> reportMissing(key, revision, digest) }
        val below = walk.anchor?.let { anchor -> sound.filter { it.revision <= anchor } }.orEmpty()
        return kept + below + walk.found
    }

    private fun List<RevisionEntry>.digestAt(old: RevisionEntry): SnapshotDigest? =
        firstOrNull { it.revision == old.revision }?.digest

    /** Once per document and record: a damaged document is not logged on every read. */
    private fun reportMissing(key: SnapshotKey, revision: Long, digest: SnapshotDigest) {
        if (reported.put(key, digest) != digest) {
            log.warn {
                "[$key]: the record of revision $revision ($digest), the parent of a later revision, is missing: the " +
                    "history below it cannot be shown to be on the head's chain, so it is not listed (revision " +
                    "$revision and older read as not retained) until the record is restored."
            }
        }
    }

    /** The head's chain, oldest first, down to the first sound entry the chain agrees with, the floor or a missing record. */
    private suspend fun walk(
        key: SnapshotKey,
        stored: StoredDocument,
        floor: Long,
        compared: Map<Long, RevisionEntry>,
        sound: Set<Long>,
    ): Walk {
        val found = ArrayList<RevisionEntry>()
        var document = decode(key, stored.document)
        var digest = stored.digest
        var size = stored.document.bodyBytes().size.toLong()
        while (true) {
            val parent = document.provenance?.parent
            val parentDigest = parent?.let { SnapshotDigest.parse(it.digest) }
            found += RevisionEntry(document.revision, digest, document.publishedAt, parentDigest, size)
            if (parent == null || parentDigest == null || parent.revision < floor) {
                return Walk(found.reversed(), null, null)
            }
            if (compared[parent.revision]?.digest == parentDigest && parent.revision in sound) {
                return Walk(found.reversed(), parent.revision, null)
            }
            val record = store.record(key, parentDigest)
                ?: return Walk(found.reversed(), null, parent.revision to parentDigest)
            document = decode(key, record)
            digest = parentDigest
            size = record.bodyBytes().size.toLong()
        }
    }

    companion object {
        /**
         * Whether [entries], the index's entries up to the head's revision, are [head]'s chain from [floor] up: the same
         * rule [check] states, the one the read relies on and the tool reports (`CTL-35`).
         */
        fun agrees(entries: List<RevisionEntry>, floor: Long, head: StoredDocument): Boolean =
            check(entries, floor, head) { null } === IndexCheck.Follows

        /**
         * The entries of [index] up to [head]'s revision: nothing above the head, since what a head that moved back (a
         * partial loss of data) left in the index is not on its chain, and a revision published after the head was read
         * is left out of this read alone. The one place the filter is written: [Revisions.history],
         * [Revisions.verifyIndex] and [Retention]'s pre-check read the index through it, so they cannot disagree.
         */
        fun entriesUpTo(index: RevisionIndex, head: StoredDocument): List<RevisionEntry> =
            index.entries.filter { it.revision <= head.document.revision }

        /** [agrees] for all of [index], as [Revisions.history] and [Retention]'s pre-check ask it. */
        fun agrees(index: RevisionIndex, head: StoredDocument): Boolean =
            agrees(entriesUpTo(index, head), index.floor, head)

        /**
         * Whether [entries], the index's entries up to the head's revision, are [head]'s chain from [floor] up, and why
         * not: the last is the head's own (revision and digest), and each entry's parent is the digest of the one below
         * it. What reclamation kept below the floor is not part of the chain that is left, and is not judged. Nothing is
         * read or repaired, and no string is built when the index follows. An entry without a `parent`, which an older
         * version wrote, cannot be checked ([IndexCheck.Unchecked]); one that names another parent than the entry below
         * it is a difference. An index that ends exactly at the head's parent, with every link below holding, is behind
         * by one revision ([IndexCheck.Behind]): what a publication leaves between its head write and its index write;
         * [parentOfHead] answers the digest the head names as its parent, and is asked only then.
         */
        fun check(
            entries: List<RevisionEntry>,
            floor: Long,
            head: StoredDocument,
            parentOfHead: () -> SnapshotDigest?,
        ): IndexCheck {
            val revision = head.document.revision
            val live = entries.filter { it.revision >= floor }
            val last = live.lastOrNull()
                ?: return if (revision == 1L) {
                    IndexCheck.Behind(revision)
                } else {
                    IndexCheck.Differs("the index lists no revision, and the head is revision $revision")
                }
            var unchecked = 0
            for (i in 1 until live.size) {
                val parent = live[i].parent
                when {
                    parent == live[i - 1].digest -> Unit
                    parent == null -> unchecked++
                    else -> return IndexCheck.Differs(
                        "revision ${live[i].revision} names $parent as its parent, but the entry below it is " +
                            "revision ${live[i - 1].revision} (${live[i - 1].digest})"
                    )
                }
            }
            return when {
                last.revision == revision && last.digest == head.digest ->
                    if (unchecked > 0) IndexCheck.Unchecked(unchecked) else IndexCheck.Follows
                last.revision == revision - 1 && last.digest == parentOfHead() -> IndexCheck.Behind(revision)
                else -> IndexCheck.Differs(
                    "the index ends at revision ${last.revision} (${last.digest}), the head is revision " +
                        "$revision (${head.digest})"
                )
            }
        }

        /** How many entries from the lowest hold: the lowest, and each next one whose parent is the one before. */
        private fun soundPrefix(entries: List<RevisionEntry>): Int {
            var sound = minOf(entries.size, 1)
            while (sound < entries.size && entries[sound].parent == entries[sound - 1].digest) {
                sound++
            }
            return sound
        }
    }
}
