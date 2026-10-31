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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.document.ShadowWindow

/** What [Changes.publish] and its shadow windows decide from the change and the head alone. */
internal object PublishRules {
    private const val CONFLICT = 409
    private const val UNPROCESSABLE = 422
    private const val PRECONDITION_REQUIRED = 428

    /** Whether [head] is [change], published over its base: the head's provenance names the change and that base. */
    fun publishedBy(change: ChangeRequest, head: Head?): Boolean {
        val provenance = head?.document?.provenance ?: return false
        return provenance.change == change.id.value &&
            provenance.parent?.digest == change.base.digest?.toString() &&
            (provenance.parent?.revision ?: 0) == change.base.revision
    }

    /**
     * [change]'s own revision, the one after its base, when an earlier publish wrote it: the head, or a revision the
     * chain still holds when the head has moved on since (review item 13); `null` when none is [change]'s.
     */
    suspend fun ownRevision(revisions: Revisions, change: ChangeRequest, head: Head?): Head? {
        val revision = change.base.revision + 1
        val own = when {
            head == null || head.revision < revision -> null
            head.revision == revision -> head
            else -> revisions.at(change.key, revision)
        }
        return own?.takeIf { publishedBy(change, it) }
    }

    fun referenced(outcome: PublishOutcome.Referenced): List<Finding> =
        outcome.referrers.map { (policy, referrers) ->
            Finding(
                "REFERENCES",
                "$.policies",
                "[${policy.value}] is referred to by ${referrers.map { it.toString() }.sorted()}",
                UNPROCESSABLE
            )
        }

    /** How a publish is recorded: who published, who approved what needed approval, and the shadow window. */
    fun publication(change: ChangeRequest, publisher: Operator, rechecked: ChangeRequest): Publication {
        val approvedBy = listOfNotNull(
            change.approval?.approver?.takeIf { rechecked.escalations.any { !it.informational } }
        )
        val window = when {
            change.shadow != null -> ShadowWindow(change.shadow.window, skipped = false)
            change.skipShadow != null -> ShadowWindow(null, skipped = true)
            else -> null
        }
        return Publication(change.id.value, change.kind, publisher.actor, approvedBy, window)
    }

    fun outcomeOf(change: ChangeRequest, settled: ChangeRequest): ChangeEvent.Kind = when {
        settled.state != ChangeState.PUBLISHED -> ChangeEvent.Kind.FAILED
        change.kind == ProvenanceKind.BREAK_GLASS && change.operations.all {
            it is Operation.RemoveBinding
        } -> ChangeEvent.Kind.REVOKED
        else -> ChangeEvent.Kind.PUBLISHED
    }

    /**
     * A change whose window closed blocked never publishes (409); one whose summary needs acknowledging publishes only
     * with an [ack] of exactly that window and those counts (428): the publisher has seen the numbers that stand.
     */
    fun acknowledged(change: ChangeRequest, ack: ShadowAck?) {
        val state = change.shadow ?: return
        val problem = when {
            state.closed == null -> CONFLICT to "the window of change ${change.id.value} is open"
            state.closed.blocked -> CONFLICT to "an instance could not compile or evaluate the candidate of change ${change.id.value}"
            state.closed.needsAck && !matches(ack, state.window, state.closed) ->
                PRECONDITION_REQUIRED to "publishing change ${change.id.value} needs an acknowledgement of window ${state.window}: ${state.closed.counts}"
            else -> null
        }
        problem?.let { (status, message) -> throw ChangeRejectedException(status, message) }
    }

    /** Whether [ack] names [window] and exactly the counts of [summary]; zeros may be left out. */
    fun matches(ack: ShadowAck?, window: String, summary: ShadowSummary): Boolean =
        ack != null && ack.window == window && ack.counts.filterValues { it != 0L } == summary.counts.filterValues { it != 0L }

    /**
     * Whether [change] only creates or revokes break-glass bindings (RFC 0007 Q12): such a change has no shadow window.
     * A change of kind `BREAK_GLASS` always is one.
     */
    fun exempt(change: ChangeRequest, head: Head?): Boolean {
        if (change.kind == ProvenanceKind.BREAK_GLASS) {
            return true
        }
        val glass = head?.document?.bindings.orEmpty().filter { it.breakGlass }.map { it.id }.toSet()
        return change.operations.isNotEmpty() && change.operations.all {
            (it is Operation.PutBinding && it.binding.breakGlass) || (it is Operation.RemoveBinding && it.id in glass)
        }
    }
}
