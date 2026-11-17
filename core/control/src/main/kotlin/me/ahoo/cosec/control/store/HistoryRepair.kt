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

/**
 * Keeps the revision index the head's chain (#1536). The head is the authority, and a document's digest covers its
 * `parent`, so one index entry whose digest is the chain's vouches for every entry below it, provided the index was
 * only ever appended to over a parent it does not contradict ([linked]): the invariant is kept at the write, not paid
 * for at every read.
 *
 * - At read, the entry at the head's revision must carry the head's digest ([agrees]): one comparison, no record read.
 * - Otherwise the index is behind the head, or on another branch (the head moved back and a number was reused):
 *   [rebuild] walks the chain from the head down to the first entry the index has right, and replaces what differs.
 */
internal class HistoryRepair(
    private val store: ControlStore,
    private val writes: RevisionWrites,
    /** A stored document decoded, as [Revisions.decode] decodes it. */
    private val decode: (SnapshotKey, SnapshotDocument) -> PolicyDocument,
) {
    /**
     * [indexed], rebuilt from the chain of [stored], the head of [key]; [entries] are its entries up to the head. Each
     * revision of the chain that the index has otherwise is replaced, but only where the index, at the write, still
     * has the entry the walk compared: another instance's repair or a republication of that number is never
     * overwritten. Nothing below the floor of the attempt is written (#1265), and what is replaced goes to
     * [RevisionIndex.reclaiming], where the next reclamation deletes it unless something still holds it.
     */
    suspend fun rebuild(
        key: SnapshotKey,
        stored: StoredDocument,
        indexed: RevisionIndex,
        entries: List<RevisionEntry>,
    ): List<RevisionEntry> {
        val compared = entries.associateBy { it.revision }
        val chain = walk(key, stored, indexed.floor, compared)
        var replaced: List<RevisionEntry> = emptyList()
        var replacements: List<RevisionEntry> = emptyList()
        val written = writes.updateIndex(key) { index ->
            replacements = chain.filter { entry ->
                entry.revision >= index.floor && entry.digest !in index.reclaiming &&
                    index.entries.firstOrNull { it.revision == entry.revision }?.digest ==
                    compared[entry.revision]?.digest
            }
            replaced = replacements.mapNotNull { compared[it.revision] }
            val revisions = replacements.map { it.revision }.toSet()
            index.copy(
                entries = (index.entries.filter { it.revision !in revisions } + replacements).sortedBy { it.revision },
                pending = index.pending.filter { pending -> replacements.none { it.digest == pending.digest } },
                reclaiming = (index.reclaiming + replaced.map { it.digest }).distinct(),
            )
        }
        if (!written) {
            warn("[$key]: the rebuilt history could not be written back to the index")
        } else if (replaced.isNotEmpty()) {
            val kept = replacements.associateBy { it.revision }
            log.warn {
                "[$key]: the head's chain differs from the revision index (a head that moved back and a revision " +
                    "number reused, or a partly restored store); the index now follows the head. Dropped, kept: " +
                    replaced.joinToString("; ") { "revision ${it.revision} ${it.digest}, ${kept[it.revision]?.digest}" } +
                    ". The dropped records will be reclaimed."
            }
        }
        val revisions = chain.map { it.revision }.toSet()
        return (entries.filter { it.revision !in revisions } + chain).sortedBy { it.revision }
    }

    /** The head's chain, oldest first, down to the first revision the index has right, the floor or a missing record. */
    private suspend fun walk(
        key: SnapshotKey,
        stored: StoredDocument,
        floor: Long,
        compared: Map<Long, RevisionEntry>,
    ): List<RevisionEntry> {
        val found = ArrayList<RevisionEntry>()
        var document = decode(key, stored.document)
        var digest = stored.digest
        while (true) {
            found += RevisionEntry(document.revision, digest, document.publishedAt)
            val parent = document.provenance?.parent ?: break
            val parentDigest = SnapshotDigest.parse(parent.digest)
            if (parent.revision < floor || compared[parent.revision]?.digest == parentDigest) {
                break
            }
            document = store.record(key, parentDigest)?.let { decode(key, it) } ?: break
            digest = parentDigest
        }
        return found.reversed()
    }

    companion object {
        /** Whether [last], the last entry up to the head, is the head's own: its revision and its digest. */
        fun agrees(last: RevisionEntry?, head: StoredDocument): Boolean =
            last != null && last.revision == head.document.revision && last.digest == head.digest

        /**
         * Whether an entry published over [parent] (`null` for a document's first revision) may be appended to [index]:
         * the index does not contradict [parent], that is, it has no entry of that revision with another digest. A
         * parent it does not list yet is no contradiction: a publish settles late, beside the later ones, and a
         * revision that never gets indexed is found by a read of the chain (the head's entry must be the head's own).
         * Otherwise the index stays behind the head, and the next read rebuilds it from the chain.
         */
        fun linked(index: RevisionIndex, parent: ParentRevision?): Boolean {
            val listed = parent?.let { p -> index.entries.firstOrNull { it.revision == p.revision } }
            return listed == null || listed.digest.toString() == parent.digest
        }
    }
}
