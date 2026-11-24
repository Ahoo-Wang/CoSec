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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.RetentionPolicy
import me.ahoo.cosec.runtime.HeapBudget
import java.time.Duration

/**
 * The control plane's cadences and limits that are computed or constant, not configured (the rule of root `AGENTS.md`:
 * what can be computed is not configured). An operator states the evidence a change needs
 * (`cosec.control.shadow.min-duration`, `min-samples`, `max-duration`) and how much history is kept
 * (`cosec.control.revisions.max-count`, `min-age`); nothing below carries knowledge only an operator has.
 */
@InternalCoSecApi
object ControlDerived {
    /**
     * How often every instance reclaims revisions and expires open changes, each document under a lease. A constant:
     * what is reclaimed is decided by the retention the operator states, and this only decides how soon after a record
     * becomes eligible it goes. A pass over documents with nothing to reclaim costs one index read and one head read each.
     */
    val RECLAIM_INTERVAL: Duration = RetentionPolicy.RECLAIM_PASS

    /**
     * How old a record not on a revision chain, or a change listed but never written, is before it is removed: one
     * reclaim pass. A write in progress takes seconds, so a record that has outlived a whole pass is not one, and the
     * pass is the only thing that looks. It must not be shorter than [RECLAIM_INTERVAL]: a pass would then remove a record
     * whose first write is still in flight.
     */
    val ORPHAN_AGE: Duration = RECLAIM_INTERVAL

    /** The shadow pass looks at the open windows this many times within the least a window lives. */
    const val SHADOW_CHECKS_PER_MIN_DURATION: Int = 15

    /** The shadow pass is never more often than this: it takes a lease and reads every shadowing change. */
    val SHADOW_CHECK_FLOOR: Duration = Duration.ofMillis(250)

    /** ... and never less often than this: a window closes up to one interval late. */
    val SHADOW_CHECK_CEILING: Duration = Duration.ofMinutes(1)

    /**
     * How often shadowing changes are looked at, by one instance at a time: a [SHADOW_CHECKS_PER_MIN_DURATION]th of
     * [minDuration] (`cosec.control.shadow.min-duration`), the least a window lives, since a window cannot close earlier
     * and a window closes up to one interval late; within [SHADOW_CHECK_FLOOR] and [SHADOW_CHECK_CEILING]. A minute at
     * the default quarter of an hour.
     */
    fun shadowCheckInterval(minDuration: Duration): Duration =
        minDuration.dividedBy(
            SHADOW_CHECKS_PER_MIN_DURATION.toLong()
        ).coerceIn(SHADOW_CHECK_FLOOR, SHADOW_CHECK_CEILING)

    /** What a request body may hold besides the document: the operations' JSON around it. */
    const val BODY_ROOM_BYTES: Long = 2L * 1024 * 1024

    /**
     * The largest request body the control routes read: the document cap ([ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES]) and
     * [BODY_ROOM_BYTES], 10 MB. Larger is a 400 (`CTL-41`); raising the document cap raises it too.
     */
    const val MAX_BODY_BYTES: Long = ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong() + BODY_ROOM_BYTES

    /**
     * What holds the snapshot documents' quarter of the heap (`HeapBudget.documents`, `SNP-24`) on a host that runs the
     * control plane (DR1-F-5): the host pipeline's snapshot source, the control plane's own, and the documents
     * `simulate`, `explain`, `analyze` and the lock-out check compile, one cache per host. Each holds a third: the
     * control plane's cache holds compiled documents as the sources do, so it takes their room, not a share of its own,
     * and `HeapBudget`'s shares still sum to half the heap.
     */
    const val DOCUMENT_HOLDERS: Int = 3

    /** The heap the control plane's document cache holds at most: a [DOCUMENT_HOLDERS]th of the documents' quarter. */
    fun documentCacheBytes(budget: HeapBudget = HeapBudget.of()): Long = budget.documents / DOCUMENT_HOLDERS

    /** The headers a request has on Spring Boot's servers by default (`server.max-http-request-header-size`). */
    private const val REQUEST_HEADER_BYTES: Long = 8L * 1024

    /**
     * The largest body of a route that carries no document (DR1-F-3): every route but a proposal, an `:apply` and their
     * checks. Eight times the [REQUEST_HEADER_BYTES] a real request's headers take: the largest such body is a simulated
     * request, one request's headers, attributes, roles and policies; the others (a reason, an acknowledgement, a
     * break-glass request, a rollback) are smaller still. Larger is a 400 before the body is parsed, so a request nobody
     * authorized yet costs about its size, not the tree a parser would build of [MAX_BODY_BYTES].
     */
    const val SMALL_BODY_BYTES: Long = 8 * REQUEST_HEADER_BYTES

    /**
     * The work budget's share of the heap (`CTL-85`): an eighth, a quarter of the half `HeapBudget`'s shares leave to
     * everything else. Not a `HeapBudget` share: it bounds work in flight, not memory held for performance.
     */
    const val WORK_SHARE: Long = 8

    /**
     * What a JSON body becomes as a tree, per byte, at worst: measured on the shapes that make the most nodes of the
     * fewest bytes (`ControlBodyTreeTest`, which holds the trees under it).
     */
    const val BODY_TREE_FACTOR: Long = 64

    /** A candidate's compiled form per byte of its document: `DocumentCache.COMPILED_FACTOR`, held equal by a test. */
    const val CANDIDATE_FACTOR: Long = 8

    /** Work weighing at most this holds nothing (`CTL-85`): what a route that carries no document's body becomes. */
    const val FREE_WORK_BYTES: Long = SMALL_BODY_BYTES * BODY_TREE_FACTOR

    /** The work budget of a heap of [heap] bytes: a [WORK_SHARE]th. */
    fun workBudgetBytes(heap: Long = Runtime.getRuntime().maxMemory()): Long = heap / WORK_SHARE

    /** How long a call waits for the work budget at the default pipeline deadline, 5 s: `StageBudgets.workWait`. */
    val DEFAULT_WORK_WAIT: Duration = Duration.ofMillis(2_500)

    /** The host's work budget (`CTL-85`): a [WORK_SHARE]th of the heap, a call waiting at most [wait]. */
    fun workBudget(wait: Duration = DEFAULT_WORK_WAIT, heap: Long = Runtime.getRuntime().maxMemory()): WorkBudget =
        WorkBudget(workBudgetBytes(heap), FREE_WORK_BYTES, wait)

    /** What a body of [bytes] that carries a document weighs: its tree, and the candidate compiled from it. */
    fun bodyWeight(bytes: Long): Long = bytes * (BODY_TREE_FACTOR + CANDIDATE_FACTOR)

    /** The work budget, and what the largest body weighs at worst and how many run at once, for the startup line. */
    fun describeWork(heap: Long = Runtime.getRuntime().maxMemory()): String {
        val budget = workBudgetBytes(heap)
        val largest = bodyWeight(MAX_BODY_BYTES)
        return "work budget=${budget / MEBIBYTE}MB (1/$WORK_SHARE of the heap), the largest body weighs " +
            "${largest / MEBIBYTE}MB at worst: ${maxOf(1, budget / largest)} at once"
    }

    /** One line for the startup log: what was derived, from what. */
    fun describe(minDuration: Duration): String =
        "reclaim-interval=$RECLAIM_INTERVAL (constant), orphan-age=$ORPHAN_AGE (one reclaim pass), " +
            "shadow check interval=${shadowCheckInterval(minDuration)} (1/$SHADOW_CHECKS_PER_MIN_DURATION of " +
            "cosec.control.shadow.min-duration $minDuration), max body=${MAX_BODY_BYTES / MEBIBYTE}MB " +
            "(the ${ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES / MEBIBYTE}MB document cap and ${BODY_ROOM_BYTES / MEBIBYTE}MB), " +
            "${SMALL_BODY_BYTES / KIBIBYTE}KB for a route that carries no document (8 request headers' worth), " +
            "document cache=${documentCacheBytes() / MEBIBYTE}MB (1/$DOCUMENT_HOLDERS of the documents' share)" +
            "; " + describeWork()

    private const val MEBIBYTE = 1024L * 1024
    private const val KIBIBYTE = 1024L
}
