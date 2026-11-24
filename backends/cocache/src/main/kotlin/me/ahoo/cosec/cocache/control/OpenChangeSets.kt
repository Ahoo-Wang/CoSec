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

package me.ahoo.cosec.cocache.control

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.store.ChangePage
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.EndedChange
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The open changes as sets of record (R2C-2, R2C-4, `CTL-54`): `doc:<docKey>` holds the ids of a document's open
 * changes, `state:<STATE>` holds `<docKey>#<id>@<digest>` of the changes in that state, and `kept` the marker. Each write
 * is one `SADD` or `SREM`, so changes never contend on one value.
 *
 * A state member names the write that put it there, by the digest of the record it wrote, as a back-reference names
 * its revision (`CTL-25`): before a change's record is written, its document's set gets its id and its state's set the
 * member of the new record; after the write landed, the member of the record it replaced (`expected`) leaves the
 * previous state's set, and the id leaves the document's once the change is terminal. A change that comes back to a
 * state comes back as another record, so another member, and no removal of an older one can take it. A crash, a failed
 * removal or a lost conditional write leaves an extra member, never a missing one.
 *
 * A reader lists a change once, whatever members name it, when its record is in the state. It keeps the member of
 * that record, removes the others at once when the change is terminal (terminal is final), and otherwise only once
 * this instance has seen them so for [missingGrace]: a member ahead of its record is a write in flight. A member of
 * the form without a digest is never the record's, so it goes by the same rule. One whose record does not read is
 * listed as unreadable.
 *
 * They are trusted only once kept and while no list of the format before 8.0.0-beta.5 exists ([kept]): the conversion
 * writes the marker after it listed every change of the old list, and the control plane writes it only where there is
 * no old list. An older control plane that writes the old list again puts the store back to not kept.
 */
internal class OpenChangeSets(
    private val sets: ControlSets,
    /** Whether the open-change list of the format before 8.0.0-beta.5 is there. */
    private val legacyListed: suspend () -> Boolean,
    /** The change [ChangeId] as stored, with its digest; null when there is none; a record that does not read throws. */
    private val record: suspend (ChangeId) -> Versioned<ChangeRequest>?,
    private val clock: Clock,
    /** How long a member whose record is missing stays: a first write in flight is missing for a moment. */
    private val missingGrace: Duration,
) {
    /**
     * When this instance first found a member's change missing or not listed there, by set, then member. Each read of a
     * set forgets the members it no longer holds (DR1-F-20): another writer or instance may have removed them, and an
     * entry kept for them would stay for the process's life. So it holds no more than the sets hold.
     */
    private val staleSince = ConcurrentHashMap<String, MutableMap<String, Instant>>()

    /** How many members this instance keeps a time for: what the sets hold at most. */
    internal fun staleEntries(): Int = staleSince.values.sumOf { it.size }

    suspend fun kept(): Boolean = KEPT in sets.changeMembers(KEPT) && !legacyListed()

    /** The marker, written only where no old list is there (`CTL-54`). */
    suspend fun keep() {
        if (!legacyListed()) sets.addChangeMembers(KEPT, listOf(KEPT))
    }

    /** The marker, written by the conversion once it listed every change of the old list. */
    suspend fun keepConverted() = sets.addChangeMembers(KEPT, listOf(KEPT))

    /**
     * Before [change] is written as [written]: in its document's set and, unless it is terminal, in its state's; a
     * terminal one in its document's ended set instead (`CTL-83`), under its end, so a write that lost leaves an extra
     * member, never a missing one.
     */
    suspend fun listBefore(change: ChangeRequest, written: SnapshotDigest) {
        sets.addChangeMembers(documentKey(change.key), listOf(change.id.value))
        if (change.state.terminal) {
            sets.addChangeMembers(endedKey(change.key), listOf(endedMember(change.updatedAt, change.id)))
        } else {
            sets.addChangeMembers(stateKey(change.state), listOf(member(change.key, change.id, written)))
        }
    }

    /** [ControlStore.endedChanges]: one entry a change, at its latest end (an earlier one is a write that lost). */
    suspend fun ended(document: SnapshotKey, endedBefore: Instant, limit: Int): List<EndedChange> =
        sets.changeMembers(endedKey(document)).mapNotNull(::parseEnded)
            .groupBy { it.id }
            .map { (_, ends) -> ends.maxBy { it.endedAt } }
            .filter { it.endedAt.isBefore(endedBefore) }
            .sortedWith(compareBy({ it.endedAt }, { it.id.value }))
            .take(limit)

    /** Every member of [id] out of [document]'s ended set: after its record was removed. */
    suspend fun unlistEnded(document: SnapshotKey, id: ChangeId) {
        val key = endedKey(document)
        sets.removeChangeMembers(key, sets.changeMembers(key).filterTo(HashSet()) { parseEnded(it)?.id == id })
    }

    /**
     * After [change] was written as [written] over the record [expected] in [previous] (`null`: none): the member of
     * [expected] leaves [previous]'s set, and nothing else does, so a later write that brought the change back there,
     * as another record, keeps its member. Out of its document once it is terminal. A failure warns.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun unlistAfter(
        change: ChangeRequest,
        previous: ChangeState?,
        expected: SnapshotDigest?,
        written: SnapshotDigest,
    ) {
        try {
            val replaced = expected?.takeIf { it != written }
            if (previous != null && !previous.terminal && replaced != null) {
                sets.removeChangeMembers(stateKey(previous), setOf(member(change.key, change.id, replaced)))
            }
            if (change.state.terminal) sets.removeChangeMembers(documentKey(change.key), setOf(change.id.value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Change [${change.id.value}] stays listed where it no longer is; a later read drops it." }
        }
    }

    suspend fun openChanges(document: SnapshotKey): List<ListedChange> {
        val found = ArrayList<ListedChange>()
        eachOpenChange(document, null) {
            found += it
            true
        }
        return found
    }

    /** The open changes of [document] past [after], read one at a time as [visit] asks for them (DR2-E-4). */
    suspend fun eachOpenChange(document: SnapshotKey, after: String?, visit: suspend (ListedChange) -> Boolean) {
        val key = documentKey(document)
        val members = sets.changeMembers(key)
        forgetUnheld(key, members)
        for (id in members.sorted()) {
            if (after != null && id <= after) continue
            val listed = read(document, ChangeId(id), key, id) { !it.state.terminal } ?: continue
            if (!visit(listed)) return
        }
    }

    /** The changes in [state], by `<docKey>#<id>`, the cursor [after] and [ChangePage.next] are: one entry per change. */
    suspend fun changes(
        state: ChangeState,
        after: String?,
        limit: Int,
        documents: (SnapshotKey) -> Boolean
    ): ChangePage {
        val members = sets.changeMembers(stateKey(state))
        forgetUnheld(stateKey(state), members)
        val byChange = members.mapNotNull { member ->
            parse(member)?.let { (document, id) -> cursor(document, id) to member }
        }.groupBy({ it.first }, { it.second })
        val keys = byChange.keys.sorted().filter { after == null || it > after }
        val found = ArrayList<ListedChange>()
        keys.forEachIndexed { index, key ->
            val (document, id) = checkNotNull(parse(byChange.getValue(key).first()))
            if (!documents(document)) return@forEachIndexed
            inState(state, document, id, byChange.getValue(key))?.let(found::add)
            if (found.size == limit) return ChangePage(found, key.takeIf { index < keys.lastIndex })
        }
        return ChangePage(found, null)
    }

    /** The change [members] name, if its record is in [state]; the members that are not its record's are stale. */
    private suspend fun inState(
        state: ChangeState,
        document: SnapshotKey,
        id: ChangeId,
        members: List<String>,
    ): ListedChange? {
        val key = stateKey(state)
        val stored = try {
            record(id)
        } catch (_: SnapshotFormatException) {
            return ListedChange.Unreadable(id, document)
        }
        val change = stored?.value
        if (change != null && change.state.terminal) {
            members.forEach { forget(key, it) }
            sets.removeChangeMembers(key, members.toSet())
            return null
        }
        val listed = change?.takeIf { it.state == state && it.key == document }
        val current = listed?.let { member(document, id, checkNotNull(stored).digest) }
        members.filter { it != current }.forEach { stale(key, it) }
        current?.let { forget(key, it) }
        return listed?.let { ListedChange.Readable(it) }
    }

    /** The change a document's member names, if [listed] still holds of it; else the member is stale and null. */
    private suspend fun read(
        document: SnapshotKey,
        id: ChangeId,
        key: String,
        member: String,
        listed: (ChangeRequest) -> Boolean,
    ): ListedChange? {
        val change = try {
            record(id)?.value
        } catch (_: SnapshotFormatException) {
            return ListedChange.Unreadable(id, document)
        }
        if (change != null && change.state.terminal) {
            forget(key, member)
            sets.removeChangeMembers(key, setOf(member))
            return null
        }
        if (change == null || !listed(change) || change.key != document) {
            stale(key, member)
            return null
        }
        forget(key, member)
        return ListedChange.Readable(change)
    }

    /** A member whose change is missing or not listed there: removed once this instance has seen it so for the grace. */
    private suspend fun stale(key: String, member: String) {
        val since = staleSince.computeIfAbsent(key) { ConcurrentHashMap() }.getOrPut(member) { clock.instant() }
        if (since.plus(missingGrace).isBefore(clock.instant())) {
            sets.removeChangeMembers(key, setOf(member))
            forget(key, member)
        }
    }

    private fun forget(key: String, member: String) {
        staleSince.computeIfPresent(key) { _, members -> members.apply { remove(member) }.ifEmpty { null } }
    }

    /**
     * [key] was just read as holding [held]: the time of any other member is forgotten. A member a write adds after the
     * read and a reader then finds stale starts its grace again at the next read, which only keeps it longer.
     */
    private fun forgetUnheld(key: String, held: Set<String>) {
        staleSince.computeIfPresent(key) { _, members -> members.apply { keys.retainAll(held) }.ifEmpty { null } }
    }

    internal companion object {
        private val log = KotlinLogging.logger {}
        const val KEPT = "kept"

        fun documentKey(document: SnapshotKey) = "doc:$document"

        fun stateKey(state: ChangeState) = "state:${state.name}"

        /** A document's ended changes (`CTL-83`): `<epoch millis of its end>#<id>`. */
        fun endedKey(document: SnapshotKey) = "ended:$document"

        fun endedMember(endedAt: Instant, id: ChangeId) = "${endedAt.toEpochMilli()}#${id.value}"

        /** An ended member, split at its first `#` (millis hold none); null for one of another form. */
        fun parseEnded(member: String): EndedChange? {
            val millis = member.substringBefore('#', "").toLongOrNull() ?: return null
            val id = member.substringAfter('#', "")
            return if (id.isEmpty()) null else EndedChange(ChangeId(id), Instant.ofEpochMilli(millis))
        }

        /** A change's place in the order of a state's listing, and the cursor of its pages. */
        fun cursor(document: SnapshotKey, id: ChangeId) = "$document#${id.value}"

        /** The member of the record [digest] of change [id]. */
        fun member(document: SnapshotKey, id: ChangeId, digest: SnapshotDigest) = "${cursor(document, id)}@$digest"

        /**
         * `<docKey>#<id>@<digest>`, split at the last `#` (an id never holds one) and the first `@` after it (neither
         * does an id); a member without `@`, of a preview before the digest, names the change all the same. Null for a
         * member of a later form.
         */
        fun parse(member: String): Pair<SnapshotKey, ChangeId>? {
            val at = member.lastIndexOf('#')
            if (at <= 0 || at == member.length - 1) return null
            val document = SnapshotKeyEncoder.decode(member.substring(0, at)) ?: return null
            val id = member.substring(at + 1).substringBefore('@')
            return if (id.isEmpty()) null else document to ChangeId(id)
        }
    }
}
