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
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The open changes as sets of record (R2C-2, R2C-4, `CTL-54`): `doc:<docKey>` holds the ids of a document's open
 * changes, `state:<STATE>` holds `<docKey>#<id>` of the changes in that state, and `kept` the marker. Each write is one
 * `SADD` or `SREM`, so changes never contend on one value.
 *
 * They are a superset of the open changes: before a change's record is written, it is added to its document's set and
 * to its state's; after, it is removed from what no longer applies (every other state, and its document once it is
 * terminal). A crash between the two leaves an extra member, never a missing one: a reader skips a member whose change
 * is terminal, in another state or missing, and removes it. One whose record does not read is listed as unreadable.
 *
 * They are trusted only once kept and while no list of the format before 8.0.0-beta.5 exists ([kept]): the conversion
 * writes the marker after it listed every change of the old list, and the control plane writes it only where there is
 * no old list. An older control plane that writes the old list again puts the store back to not kept.
 */
internal class OpenChangeSets(
    private val sets: ControlSets,
    /** Whether the open-change list of the format before 8.0.0-beta.5 is there. */
    private val legacyListed: suspend () -> Boolean,
    /** The change [ChangeId] as stored; null when there is none; a record that does not read throws. */
    private val record: suspend (ChangeId) -> ChangeRequest?,
    private val clock: Clock,
    /** How long a member whose record is missing stays: a first write in flight is missing for a moment. */
    private val missingGrace: Duration,
) {
    /** When this instance first found a member's record missing, by member. */
    private val missingSince = ConcurrentHashMap<String, Instant>()

    suspend fun kept(): Boolean = KEPT in sets.changeMembers(KEPT) && !legacyListed()

    /** The marker, written only where no old list is there (`CTL-54`). */
    suspend fun keep() {
        if (!legacyListed()) sets.addChangeMembers(KEPT, listOf(KEPT))
    }

    /** The marker, written by the conversion once it listed every change of the old list. */
    suspend fun keepConverted() = sets.addChangeMembers(KEPT, listOf(KEPT))

    /** Before [change] is written: in its document's set and, unless it is terminal, in its state's. */
    suspend fun listBefore(change: ChangeRequest) {
        sets.addChangeMembers(documentKey(change.key), listOf(change.id.value))
        if (!change.state.terminal) sets.addChangeMembers(stateKey(change.state), listOf(member(change.key, change.id)))
    }

    /** After [change] was written: out of every other state, and out of its document once it is terminal. A failure warns. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun unlistAfter(change: ChangeRequest) {
        try {
            OPEN_STATES.filter { it != change.state }.forEach {
                sets.removeChangeMembers(stateKey(it), setOf(member(change.key, change.id)))
            }
            if (change.state.terminal) sets.removeChangeMembers(documentKey(change.key), setOf(change.id.value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Change [${change.id.value}] stays listed where it no longer is; a later read drops it." }
        }
    }

    suspend fun openChanges(document: SnapshotKey): List<ListedChange> =
        sets.changeMembers(documentKey(document)).sorted().mapNotNull { id ->
            read(document, ChangeId(id), documentKey(document), id) { !it.state.terminal }
        }

    suspend fun changes(state: ChangeState, after: String?, limit: Int, documents: (SnapshotKey) -> Boolean): ChangePage {
        val members = sets.changeMembers(stateKey(state)).sorted().filter { after == null || it > after }
        val found = ArrayList<ListedChange>()
        members.forEachIndexed { index, member ->
            val (document, id) = parse(member) ?: return@forEachIndexed
            if (!documents(document)) return@forEachIndexed
            read(document, id, stateKey(state), member) { it.state == state }?.let(found::add)
            if (found.size == limit) return ChangePage(found, member.takeIf { index < members.lastIndex })
        }
        return ChangePage(found, null)
    }

    /** The change a member names, if [listed] still holds of it; else the member is dropped and null. */
    private suspend fun read(
        document: SnapshotKey,
        id: ChangeId,
        key: String,
        member: String,
        listed: (ChangeRequest) -> Boolean,
    ): ListedChange? {
        val change = try {
            record(id)
        } catch (_: SnapshotFormatException) {
            return ListedChange.Unreadable(id, document)
        }
        if (change == null) {
            val since = missingSince.getOrPut("$key|$member") { clock.instant() }
            if (since.plus(missingGrace).isBefore(clock.instant())) {
                sets.removeChangeMembers(key, setOf(member))
                missingSince -= "$key|$member"
            }
            return null
        }
        missingSince -= "$key|$member"
        if (!listed(change) || change.key != document) {
            sets.removeChangeMembers(key, setOf(member))
            return null
        }
        return ListedChange.Readable(change)
    }

    internal companion object {
        private val log = KotlinLogging.logger {}
        const val KEPT = "kept"
        private val OPEN_STATES = ChangeState.entries.filterNot { it.terminal }

        fun documentKey(document: SnapshotKey) = "doc:$document"

        fun stateKey(state: ChangeState) = "state:${state.name}"

        fun member(document: SnapshotKey, id: ChangeId) = "$document#${id.value}"

        /** `<docKey>#<id>`, split at the last `#`: an id never holds one; null for a member of a later form. */
        fun parse(member: String): Pair<SnapshotKey, ChangeId>? {
            val at = member.lastIndexOf('#')
            if (at <= 0 || at == member.length - 1) return null
            val document = SnapshotKeyEncoder.decode(member.substring(0, at)) ?: return null
            return document to ChangeId(member.substring(at + 1))
        }
    }
}
