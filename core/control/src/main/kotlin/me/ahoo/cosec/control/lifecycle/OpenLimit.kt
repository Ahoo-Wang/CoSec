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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ConflictKind
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

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
    /** The host's one work budget (`CTL-85`), which the record being read is held in (DR2-E-4). */
    private val work: WorkBudget,
) {
    /** Before [author] stores a new change of [key], whose record is [bytes] long: room for it, or a `409 OPEN_LIMIT`. */
    suspend fun makeRoom(key: SnapshotKey, author: Operator, bytes: Long) {
        // One record at a time (DR2-E-4): of each only what the bound needs is kept, so the open changes of a document,
        // up to max-count times the cap of them, are never all decoded at once. Each is weighed by its size before it is
        // decoded, and the call holds the heaviest so far ([RecordReads]).
        val reads = RecordReads(work, author.tenant.value, flow.recordCap)
        val open = ArrayList<Open>()
        // A change that does not read still counts: it may be open, and it may pin a revision. It weighs its share of the
        // bytes, a document's worth, so that it never refuses a change on its own.
        var unreadable = 0
        store.eachOpenChange(key, null) { _, size, read ->
            reads.before(size)
            when (val listed = read()) {
                null -> Unit
                is ListedChange.Unreadable -> unreadable++
                is ListedChange.Readable -> flow.current(listed.change).takeUnless { it.state.terminal }?.let {
                    val bytes = size ?: ChangeRequestCodec.encode(it).size.toLong()
                    open += Open(it.id, it.createdAt, failedDraftOf(it, author), bytes)
                }
            }
            true
        }
        var count = open.size + unreadable
        var held = open.sumOf { it.bytes } + unreadable * (byteBudget / maxOpen)
        if (fits(count, held, bytes)) return
        val failed = open.filter { it.failedDraft }.minByOrNull { it.createdAt }
        if (failed != null && abandoned(failed.id, author)) {
            count -= 1
            held -= failed.bytes
            if (fits(count, held, bytes)) return
        }
        val oldest = open.filterNot { it.id == failed?.id }.minByOrNull { it.createdAt } ?: failed
            ?: throw ChangeRejectedException.conflict(ConflictKind.OPEN_LIMIT, "[$key] holds $count open changes that do not read")
        throw refusal(key, oldest.id, count, held, bytes)
    }

    /** What the bound keeps of one open change: its id, when it was made, whether it is the author's failed draft, its bytes. */
    private class Open(val id: ChangeId, val createdAt: Instant, val failedDraft: Boolean, val bytes: Long)

    /** The `409 OPEN_LIMIT` of [key], naming [oldest]: past the count of open changes, or past their bytes. */
    private fun refusal(key: SnapshotKey, oldest: ChangeId, count: Int, held: Long, bytes: Long) =
        ChangeRejectedException.openLimit(
            oldest,
            if (count >= maxOpen) {
                "[$key] holds $maxOpen open changes, the most it may (cosec.control.revisions.max-count): abandon one, " +
                    "or wait for it to expire; the oldest is ${oldest.value}"
            } else {
                "the open changes of [$key] weigh $held bytes, and this one $bytes more, over the $byteBudget bytes they " +
                    "may weigh (cosec.control.revisions.max-count times the document cap): abandon one, or wait for it " +
                    "to expire; the oldest is ${oldest.value}"
            },
        )

    /** Whether a document holding [count] open changes of [held] bytes takes one more of [bytes]. */
    private fun fits(count: Int, held: Long, bytes: Long): Boolean = count < maxOpen && held + bytes <= byteBudget

    /** Whether [change] is [author]'s own ordinary change whose checks failed: a draft, which cannot be edited. */
    private fun failedDraftOf(change: ChangeRequest, author: Operator): Boolean =
        change.author == author.actor && change.state == ChangeState.DRAFT && change.kind == ProvenanceKind.PUBLISH

    /** Abandons [draft] for room, as its own author; false when another write moved it first, or the store refused. */
    private suspend fun abandoned(draft: ChangeId, author: Operator): Boolean = try {
        val change = flow.transition(draft, ChangeStep.ABANDON) { it.copy(state = ChangeState.ABANDONED) }
        flow.audit(ChangeEvent.Kind.ABANDONED, change.abandonedForRoom(), author.actor, origin = author.origin)
        true
    } catch (e: ChangeRejectedException) {
        log.warn(e) { "Change [${draft.value}] could not be abandoned for room; the new change is refused instead." }
        false
    }
}

/** This change as an abandon for room records it (`CTL-83`): the note says why, and names its author. */
internal fun ChangeRequest.abandonedForRoom(): ChangeRequest = abandoned().copy(
    note = "abandoned for room (CTL-83); ${abandoned().note}"
)
