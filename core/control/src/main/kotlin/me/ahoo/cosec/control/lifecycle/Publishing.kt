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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.APPROVAL
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.PRECONDITION_REQUIRED
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.UNAVAILABLE
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocument
import java.time.Clock

/**
 * Publishing a `READY` change (RFC 0007 §5.4, §6.1), and what follows the head write: settling the change, recording
 * its outcome and what moved meanwhile, and a retry that finds its revision written. [Changes.publish] is its entry.
 */
internal class Publishing(
    private val flow: ChangeLifecycle,
    private val revisions: Revisions,
    private val clock: Clock,
    private val authority: ChangeAuthority,
    private val candidates: CandidateChecks,
    private val slots: StagingSlots,
    private val listeners: ChangeListeners,
    private val movedHeads: MovedHeads,
) {
    /**
     * Publishes a `READY` change. The head must still be its base, and the candidate must compile against the current
     * heads; then it is written as [Revisions.publish] writes (record first, `putIfMatch` on the base digest). Anything
     * else leaves the head as it is: a moved base makes the change `CONFLICTED`, a candidate that no longer compiles
     * (another document changed under it) `DRAFT` with the findings. The slot is released either way.
     *
     * The [publisher] needs what publishing it needs ([PublishRules.publishing]) and every target of the operations, now
     * (403, 503), and
     * the grant analysis runs again as them on the current heads. When that finds escalations the change's approval
     * does not validly cover (missing, for another candidate, expired, the author's or the publisher's own), the change
     * goes back to `AWAITING_APPROVAL` with them, keeping its slot, and nothing is published.
     *
     * From the head write on, it does not respond to cancellation: the change is settled, its outcome audited and what
     * moved meanwhile recorded, unless a store or a listener fails; what moved is recorded even then. The checks and
     * the writes before the head's respond to it. A head write that meets this change's own revision, written by another
     * call meanwhile, leaves the change to that call to settle and record (R4-2), and returns it as it stands.
     *
     * [again] is the reason of a revoker who publishes a stalled revoke by revoking its binding again ([BreakGlass]):
     * the publish's records name them and it (R4-5).
     */
    suspend fun publish(
        publisher: Operator,
        id: ChangeId,
        ack: ShadowAck? = null,
        again: String? = null
    ): ChangeRequest {
        val change = flow.visible(publisher, id)
        authority.require(publisher, change.key, PublishRules.publishing(change))
        if (change.state == ChangeState.PUBLISHED && slots.holds(change)) {
            val recorded = change.publishedAgain(publisher, again)
            return afterHead(change, checkNotNull(change.publishedRevision)) {
                slots.released(change) { flow.audit(PublishRules.outcomeOf(change, change), recorded, publisher.actor) }
            }
        }
        PublishRules.requireState(change, ChangeStep.PUBLISH.from)
        PublishRules.acknowledged(change, ack)
        candidates.authorize(publisher, change)
        val head = revisions.head(change.key)
        if (head?.digest != change.base.digest) {
            // An earlier publish wrote it and could not settle the change: it is in effect (B1 review).
            PublishRules.ownRevision(revisions, change, head)?.let { return republished(change, it, publisher) }
            return movedOn(change, head, publisher)
        }
        val rechecked = candidates.checked(change, head, publisher)
        if (rechecked.state != ChangeState.CHECKED || rechecked.candidate != change.candidate) {
            return flow.settle(change, ChangeState.DRAFT, ChangeStep.PUBLISH, rechecked.findings)
        }
        val problem = authority.approvalProblem(change, rechecked.escalations, publisher, clock.instant())
            ?: authority.approverProblem(change, rechecked.escalations, publisher)
        problem?.let { problem ->
            return flow.transition(id, ChangeStep.PUBLISH) {
                it.copy(
                    state = ChangeState.AWAITING_APPROVAL,
                    escalations = rechecked.escalations,
                    approval = null,
                    findings = rechecked.findings + Finding(APPROVAL, "$", problem, PRECONDITION_REQUIRED),
                )
            }
        }
        val candidate = candidates.applied(change, head?.document).first!!
        PublishRules.requireAudit(
            BreakGlassRules.touches(change.operations, head?.document, clock.instant()),
            listeners.hasRequired
        )
        // Another change took the slot since this one was READY (RC1-12): it publishes nothing (review M3). Read here,
        // before the PUBLISHING record, and again right before the head write (review L2): what is left is one store
        // round trip between that read and the write, plus any pause of this instance between them (a GC, say); the
        // write is conditional on the base, not on the slot. A change preempted within it still publishes, and the one
        // that took the slot conflicts on the moved base ([movedOn]).
        if (!slots.holds(change)) {
            return flow.settle(change, ChangeState.CONFLICTED, ChangeStep.PUBLISH)
        }
        flow.audit(ChangeEvent.Kind.PUBLISHING, change.publishedAgain(publisher, again), publisher.actor)
        var published: ChangeRequest? = null
        var slotLost = false
        val outcome = revisions.publish(
            change.key,
            head,
            candidate,
            PublishRules.publication(change, publisher, rechecked),
            precondition = { slots.holds(change).also { slotLost = !it } },
        ) {
            published = written(change, it, rechecked, head?.document, candidate, publisher, again)
        }
        return when (outcome) {
            is PublishOutcome.Published -> checkNotNull(published) { "[${change.key}] was written unsettled." }
            else -> unwritten(change, outcome, slotLost, publisher, again)
        }
    }

    /**
     * [change], whose publish wrote no head ([outcome]; [slotLost] when it found the slot gone right before the write),
     * settled and recorded. A head write that met this change's own revision, written by another call meanwhile, leaves
     * it to that call to settle and record (R4-2): it comes back as it stands.
     */
    private suspend fun unwritten(
        change: ChangeRequest,
        outcome: PublishOutcome,
        slotLost: Boolean,
        publisher: Operator,
        again: String?,
    ): ChangeRequest {
        val head = revisions.head(change.key)
        if (outcome is PublishOutcome.Conflicted && PublishRules.ownRevision(revisions, change, head) != null) {
            return flow.read(change.id).value
        }
        val settled = if (outcome is PublishOutcome.Referenced) {
            flow.settle(
                change,
                ChangeState.DRAFT,
                ChangeStep.UNWRITTEN,
                PublishRules.referenced(outcome),
                setOf(ChangeState.READY)
            )
        } else {
            // A change preempted before its head write is CONFLICTED already, by the one that took its slot, whether
            // it found the slot gone or met the head that change wrote (review N5).
            flow.settle(change, ChangeState.CONFLICTED, ChangeStep.UNWRITTEN)
        }
        val note = PublishRules.unwrittenNote(outcome, slotLost) ?: settled.note
        val recorded = settled.copy(note = note).publishedAgain(publisher, again)
        flow.audit(PublishRules.outcomeOf(change, settled), recorded, publisher.actor)
        return settled
    }

    /**
     * [change], `CONFLICTED`: its document is no longer at its base. When the head is a revision of the same base, by
     * the change it took the slot from (review L2), a bootstrap, a migration or an older instance, that is recorded.
     */
    private suspend fun movedOn(change: ChangeRequest, head: Head?, publisher: Operator): ChangeRequest {
        val settled = flow.settle(change, ChangeState.CONFLICTED, ChangeStep.PUBLISH)
        PublishRules.publishedFirst(change, head)?.let {
            flow.audit(ChangeEvent.Kind.FAILED, settled.copy(note = it), publisher.actor)
        }
        return settled
    }

    /**
     * What follows the head write of [change] as [outcome]: [Revisions.publish] runs it without responding to
     * cancellation, so a client that goes away leaves the change settled and audited unless a store or listener fails.
     * What moved meanwhile is recorded whatever happens to the rest: the head is written either way.
     */
    private suspend fun written(
        change: ChangeRequest,
        outcome: PublishOutcome.Published,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
        again: String?,
    ): ChangeRequest = afterHead(change, outcome.revision) {
        var settled: ChangeRequest? = null
        var mine = true
        try {
            settled = settledPublished(change, outcome.revision)
            if (settled == null) {
                // A retry settled it meanwhile, and recorded it (review D1).
                mine = false
                return@afterHead flow.read(change.id).value
            }
            flow.audit(
                PublishRules.outcomeOf(change, settled),
                settled.publishedAgain(publisher, again),
                publisher.actor
            )
            settled
        } finally {
            if (mine) recordMoved(settled ?: change, rechecked, base, candidate, publisher)
        }
    }

    /**
     * [steps], which follow the head write of [revision] of [change], at once or on a retry, and do not respond to the
     * caller's cancellation. What fails in them does not unpublish anything (#1263), so it is answered as what happened:
     * the revision is written, and publishing the change again settles it, adding no revision. A cancellation that
     * reaches here is therefore a store's own (a timeout inside it), never the caller's, which is still there afterwards.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> afterHead(change: ChangeRequest, revision: Long, steps: suspend () -> T): T = try {
        steps()
    } catch (e: ChangeRejectedException) {
        throw e
    } catch (e: Exception) {
        throw ChangeRejectedException(
            UNAVAILABLE,
            "[${change.key}] revision $revision is written, but change ${change.id.value} could not be settled; " +
                "publishing it again settles it",
            e,
        )
    }

    /**
     * [change] moved to `PUBLISHED` at [revision] by this call, whatever state it was in: once the head is written it is
     * published. `null` when another call has already settled it, which recorded it then (review D1).
     */
    private suspend fun settledPublished(change: ChangeRequest, revision: Long): ChangeRequest? {
        val settled = try {
            flow.transition(change.id, ChangeStep.WRITTEN) {
                it.copy(state = ChangeState.PUBLISHED, publishedRevision = revision)
            }
        } catch (e: ChangeRejectedException) {
            if (flow.read(change.id).value.state == ChangeState.PUBLISHED) return null
            throw e
        }
        slots.release(settled)
        return settled
    }

    /** What moved under [published] while it was written, as an event of its own ([MovedHeads], RC1-33). */
    private suspend fun recordMoved(
        published: ChangeRequest,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
        actor: Actor = publisher.actor,
        unanalyzed: String? = null,
    ) = movedHeads.record(published, rechecked, base, candidate, publisher, unanalyzed) { found ->
        val change = published.copy(escalations = found.escalations, note = found.note)
        flow.audit(found.kind, change, actor, found.moved)
    }

    /**
     * [change], whose head write an earlier publish made and could not finish after (a lost reply, a failed store):
     * what follows the write runs now, without responding to cancellation, recorded once, by whichever call settles it
     * (review D1). The events name the publisher the revision records, not [retrier]; what moved is recorded as needing
     * review, not analyzed again with [retrier]'s rights on what the change was last checked with (review D2).
     */
    suspend fun republished(
        change: ChangeRequest,
        own: Head,
        retrier: Operator,
        taking: Boolean = false,
    ): ChangeRequest =
        withContext(NonCancellable) {
            afterHead(change, own.revision) {
                revisions.rewritten(change.key, own, first = change.base.revision == 0L)
                val by = own.document.provenance?.publishedBy ?: retrier.actor
                val settled = settledPublished(change, own.revision) ?: return@afterHead flow.read(change.id).value
                try {
                    flow.audit(PublishRules.outcomeOf(change, settled), settled, by)
                    settled
                } finally {
                    val who = "${retrier.tenant.value}/${retrier.actor.principal.value}"
                    val how = if (taking) "settled by $who while taking the slot" else "retried by $who"
                    recordMoved(settled, change, null, own.document, retrier, by, unanalyzed = "$how, not re-analyzed")
                }
            }
        }

    /**
     * The candidate [id] would publish on the current head (RFC 0007 §10.3 `simulate`), or null when its operations no
     * longer apply there. Nothing is written.
     */
    suspend fun candidateOf(id: ChangeId): PolicyDocument? {
        val change = flow.get(id)
        return candidates.applied(change, revisions.head(change.key)?.document).first
    }
}
