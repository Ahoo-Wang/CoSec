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

package me.ahoo.cosec.control.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ConflictKind
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey

private val log = KotlinLogging.logger {}

/**
 * How many open changes a document takes (`CTL-83`, DR1-E-2): none more once it holds [maxOpen], the revisions the
 * operator keeps (`cosec.control.revisions.max-count`). The count is read before the write, so proposals counted at the
 * same moment all pass: a document holds at most [maxOpen] and those written at once. An open change pins its base
 * against reclamation (RFC 0007 §5.7), so the changes open on a document pin about what the retention keeps anyway; and
 * what one tenant stores stays bounded by it.
 *
 * A change whose checks failed (`DRAFT`) cannot be edited: its operations are fixed, and a rebase replays them, so it
 * moves on only when the head it failed on does. A loop of failing `:apply`s would fill the document with them. So at
 * the bound the author's own oldest such draft is abandoned to make room, and only then is the new change refused
 * ([ConflictKind.OPEN_LIMIT], naming the oldest open change). A
 * change that can still move on is never abandoned for room, nor another author's, nor a break-glass change.
 *
 * The open records are bounded in bytes too (DR2-E-2): with the new one they weigh at most [byteBudget], `max-count`
 * times the document cap, as the document's revision records do (`CTL-84`), and each is at most
 * [ChangeRecordBudget.recordCap]. Past it the same author's draft makes room, and the change is otherwise refused with
 * the same conflict, saying it is the bytes.
 */
internal class OpenLimit(
    private val store: ControlStore,
    private val flow: ChangeLifecycle,
    private val maxOpen: Int,
    /** The most bytes the open records of a document weigh with a new one (DR2-E-2): max-count times the cap. */
    private val byteBudget: Long,
) {
    /** Before [author] stores a new change of [key], whose record is [bytes] long: room for it, or a `409 OPEN_LIMIT`. */
    suspend fun makeRoom(key: SnapshotKey, author: Operator, bytes: Long) {
        val listed = store.openChanges(key)
        // A change that does not read still counts: it may be open, and it may pin a revision. It weighs its share of the
        // bytes, a document's worth, so that it never refuses a change on its own.
        val unreadable = listed.count { it is ListedChange.Unreadable }
        val open = listed.filterIsInstance<ListedChange.Readable>()
            .map { flow.current(it.change) }
            .filterNot { it.state.terminal }
        val sizes = open.associate { it.id to ChangeRequestCodec.encode(it).size.toLong() }
        var count = open.size + unreadable
        var held = sizes.values.sum() + unreadable * (byteBudget / maxOpen)
        if (fits(count, held, bytes)) return
        val failed = open.filter { failedDraftOf(it, author) }.minByOrNull { it.createdAt }
        if (failed != null && abandoned(failed, author)) {
            count -= 1
            held -= sizes.getValue(failed.id)
            if (fits(count, held, bytes)) return
        }
        val oldest = open.filterNot { it.id == failed?.id }.minByOrNull { it.createdAt } ?: failed
            ?: throw ChangeRejectedException.conflict(ConflictKind.OPEN_LIMIT, "[$key] holds $count open changes that do not read")
        throw refusal(key, oldest, count, held, bytes)
    }

    /** The `409 OPEN_LIMIT` of [key], naming [oldest]: past the count of open changes, or past their bytes. */
    private fun refusal(key: SnapshotKey, oldest: ChangeRequest, count: Int, held: Long, bytes: Long) =
        ChangeRejectedException.openLimit(
            oldest.id,
            if (count >= maxOpen) {
                "[$key] holds $maxOpen open changes, the most it may (cosec.control.revisions.max-count): abandon one, " +
                    "or wait for it to expire; the oldest is ${oldest.id.value}"
            } else {
                "the open changes of [$key] weigh $held bytes, and this one $bytes more, over the $byteBudget bytes they " +
                    "may weigh (cosec.control.revisions.max-count times the document cap): abandon one, or wait for it " +
                    "to expire; the oldest is ${oldest.id.value}"
            },
        )

    /** Whether a document holding [count] open changes of [held] bytes takes one more of [bytes]. */
    private fun fits(count: Int, held: Long, bytes: Long): Boolean = count < maxOpen && held + bytes <= byteBudget

    /** Whether [change] is [author]'s own ordinary change whose checks failed: a draft, which cannot be edited. */
    private fun failedDraftOf(change: ChangeRequest, author: Operator): Boolean =
        change.author == author.actor && change.state == ChangeState.DRAFT && change.kind == ProvenanceKind.PUBLISH

    /** Abandons [draft] for room, as its own author; false when another write moved it first, or the store refused. */
    private suspend fun abandoned(draft: ChangeRequest, author: Operator): Boolean = try {
        val change = flow.transition(draft.id, ChangeStep.ABANDON) { it.copy(state = ChangeState.ABANDONED) }
        flow.audit(ChangeEvent.Kind.ABANDONED, change.abandonedForRoom(), author.actor, origin = author.origin)
        true
    } catch (e: ChangeRejectedException) {
        log.warn(e) { "Change [${draft.id.value}] could not be abandoned for room; the new change is refused instead." }
        false
    }
}

/** This change as an abandon for room records it (`CTL-83`): the note says why, and names its author. */
internal fun ChangeRequest.abandonedForRoom(): ChangeRequest = abandoned().copy(
    note = "abandoned for room (CTL-83); ${abandoned().note}"
)
