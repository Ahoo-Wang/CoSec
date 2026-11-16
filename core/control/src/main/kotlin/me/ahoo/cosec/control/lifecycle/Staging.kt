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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ShadowState
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.BREAK_GLASS_EXEMPT
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.CONFLICT
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.MAX_ATTEMPTS
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.STAGED
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.UNAVAILABLE
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Staging a `CHECKED` change (RFC 0007 §5.5, §6.4): taking its document's staging slot, preempting a holder where
 * it may, starting, looking at and closing the shadow window, or skipping it. [Changes.stage], [Changes.shadowed] and
 * [Changes.skipShadow] are its entries.
 */
internal class Staging(
    private val flow: ChangeLifecycle,
    private val publishing: Publishing,
    private val store: ControlStore,
    private val revisions: Revisions,
    private val clock: Clock,
    private val authority: ChangeAuthority,
    private val candidates: CandidateChecks,
    private val slots: StagingSlots,
    private val windows: ShadowWindows,
    private val preemption: Preemption,
) {
    /**
     * Takes the document's staging slot for a `CHECKED` change and starts its shadow window (RFC 0007 §6.4): the
     * candidate goes to the `candidate:` namespace, named by the window, with the revision of its base, and the change is
     * `SHADOWING`. 409 when the slot is held; a candidate that cannot be written gives the slot back (503). The slot holds
     * the document's publishing up, so [operator] needs what publishing it needs ([PublishRules.publishing]).
     *
     * Creating or revoking a break-glass binding has no window (RFC 0007 Q12): such a change goes on at once, as if
     * skipped, without `cosec:skip-shadow`.
     */
    suspend fun stage(operator: Operator, id: ChangeId): ChangeRequest {
        val change = flow.visible(operator, id)
        authority.require(operator, change.key, PublishRules.publishing(change))
        PublishRules.requireState(change, setOf(ChangeState.CHECKED))
        val head = revisions.head(change.key)
        if (PublishRules.exempt(change, head)) {
            return reserved(operator, change, head) {
                it.copy(state = PublishRules.afterWindow(it), skipShadow = BREAK_GLASS_EXEMPT)
            }
        }
        val now = clock.instant()
        val state = ShadowState("${id.value}@${now.toEpochMilli()}", now)
        val candidate = slots.candidate(change, state) { candidates.applied(change, head?.document).first!! }
        val shadowing = reserved(operator, change, head) { it.copy(state = ChangeState.SHADOWING, shadow = state) }
        slots.write(shadowing, candidate) { undo(shadowing) }
        flow.audit(ChangeEvent.Kind.SHADOW_STARTED, shadowing, operator.actor)
        return shadowing
    }

    /**
     * [change] moved out of `CHECKED` by [next], and only then given its document's staging slot (#1235): a holder is in
     * a staged state from the moment it holds the slot, so a `CHECKED` holder is one nothing is working on any more, and
     * no call still writing its candidate is taken for it. The move, one conditional write, decides between two calls
     * staging one change. A change that does not get the slot, for whatever reason, is `CHECKED` again.
     */
    private suspend fun reserved(
        operator: Operator,
        change: ChangeRequest,
        head: Head?,
        next: suspend (ChangeRequest) -> ChangeRequest,
    ): ChangeRequest {
        val reserved = flow.transition(change.id, setOf(ChangeState.CHECKED), next)
        var taken = false
        try {
            takeSlot(operator, reserved, head)
            taken = true
            return reserved
        } finally {
            if (!taken) unreserve(reserved)
        }
    }

    /** Gives [reserved] back ([StagingSlots.giveBack]), and makes the change `CHECKED` again unless it has moved on. */
    private suspend fun unreserve(reserved: ChangeRequest) = slots.giveBack(reserved) { undo(reserved) }

    private suspend fun undo(reserved: ChangeRequest) {
        flow.transition(reserved.id, setOf(reserved.state)) {
            it.copy(state = ChangeState.CHECKED, skipShadow = null, shadow = null)
        }
    }

    /**
     * Looks at the window of a `SHADOWING` change (RFC 0007 §6.4; the host calls it periodically). While it may not
     * close yet, the change stays as it is. Once it may, its summary is kept as the evidence the publisher acknowledges,
     * the candidate is withdrawn, and the change is `READY`, or `AWAITING_APPROVAL` when it has escalations (§7.5). A
     * head that moved during the window makes it `CONFLICTED`. Reports that cannot be read keep the window open (503).
     */
    suspend fun shadowed(id: ChangeId): ChangeRequest {
        val change = flow.get(id)
        PublishRules.requireState(change, setOf(ChangeState.SHADOWING))
        val state = change.shadow ?: error("change ${id.value} is shadowing without a window")
        if (revisions.head(change.key)?.digest != change.base.digest) {
            return flow.settle(change, ChangeState.CONFLICTED, from = setOf(ChangeState.SHADOWING))
        }
        @Suppress("TooGenericExceptionCaught")
        val look = try {
            windows.look(state, clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChangeRejectedException(UNAVAILABLE, "the shadow reports of ${state.window} could not be read", e)
        }
        // Every instance seen is kept, so that one dropped from the window's reporters later still has to report.
        val seen = state.copy(reporters = look.reporters)
        val summary = look.summary ?: return if (seen == state) {
            change
        } else {
            flow.transition(id, setOf(ChangeState.SHADOWING)) { it.copy(shadow = seen) }
        }
        val closed = flow.transition(id, setOf(ChangeState.SHADOWING)) {
            it.copy(state = PublishRules.afterWindow(it), shadow = seen.copy(closed = summary))
        }
        slots.withdraw(closed)
        flow.audit(ChangeEvent.Kind.SHADOWED, closed, closed.author)
        return closed
    }

    /** The propagation of [revision] of [key] to the instances (RFC 0007 §6.5): read-only, nothing waits for it. */
    suspend fun propagation(key: SnapshotKey, revision: Long): Propagation = windows.propagation(key, revision)

    /**
     * Skips the window of a `CHECKED` change (`cosec:skip-shadow`, RFC 0007 Q1): it takes the slot and is `READY`. The
     * [reason] is kept on the change and recorded in the revision's provenance as a skipped window. [operator] needs
     * `cosec:skip-shadow` on the document, unless the change only creates or revokes break-glass bindings (Q12); a change
     * with escalations goes on to `AWAITING_APPROVAL`.
     */
    suspend fun skipShadow(operator: Operator, id: ChangeId, reason: String): ChangeRequest {
        requireRequest(reason.isNotBlank()) { "Skipping the shadow window needs a reason." }
        val change = flow.visible(operator, id)
        if (PublishRules.exempt(change, revisions.head(change.key))) {
            authority.require(operator, change.key, PublishRules.publishing(change))
        } else {
            authority.require(operator, change.key, ControlActions.SKIP_SHADOW)
        }
        PublishRules.requireState(change, setOf(ChangeState.CHECKED))
        return reserved(operator, change, revisions.head(change.key)) {
            it.copy(state = PublishRules.afterWindow(it), skipShadow = reason)
        }
    }

    /**
     * Takes [change]'s document's staging slot for [operator]; a holder that no longer stages (finished, expired, gone)
     * frees it, and so does a `READY` holder whose revision the chain shows written, its publish's answer lost: it is
     * settled as that publish would have been, then the slot is free (review L1). A slot another change still holds is
     * a 409, unless [change] may preempt it ([Preemption.preempts]); a revoke of a binding that still grants, only
     * another such revoke preempts (reviews L3, N1):
     * the holder is first made `CONFLICTED`, from whichever staged state it is in by then (approved or past its window
     * meanwhile), and only then does the slot move and its candidate go (RC1-12). A holder that cannot be marked keeps
     * its slot, and the preemption is given up (review M3): no change that still stages ever loses its slot.
     */
    private suspend fun takeSlot(operator: Operator, change: ChangeRequest, head: Head?) {
        val conflicted = ArrayList<ChangeRequest>()
        var taken = false
        try {
            repeat(MAX_ATTEMPTS) {
                val current = store.stage(change.key)
                val holder = current?.value?.holder
                if (holder == change.id) {
                    taken = true
                    return
                }
                val held = holder?.let { store.change(it)?.value }?.takeIf(slots::stillStaged)
                if (held != null) {
                    val own = PublishRules.ownRevision(
                        revisions,
                        held,
                        head
                    )?.takeIf { held.state == ChangeState.READY }
                    if (own != null) {
                        publishing.republished(held, own, operator, taking = true)
                        return@repeat
                    }
                    // A revoke of a binding that still grants is taken only by another such revoke (review N1).
                    val guarded = preemption.revokesBreakGlass(held, head) && !preemption.revokesBreakGlass(change, head)
                    if (guarded || !preemption.preempts(operator, change, head)) {
                        throw ChangeRejectedException(CONFLICT, "[${change.key}] is staged by change ${held.id.value}")
                    }
                    conflicted += conflict(held, change)
                }
                if (store.putStage(change.key, current?.digest, StageSlot(change.id, clock.instant()))) {
                    taken = true
                    return
                }
            }
            throw ChangeRejectedException(CONFLICT, "the staging slot of [${change.key}] kept changing")
        } finally {
            withContext(NonCancellable) { conflicted.forEach { preempted(it, change, operator, taken) } }
        }
    }

    /** [held] made `CONFLICTED` for [by] from any staged state, before its slot moves; 409 or 503 when it cannot be. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun conflict(held: ChangeRequest, by: ChangeRequest): ChangeRequest = try {
        flow.transition(held.id, STAGED) { it.copy(state = ChangeState.CONFLICTED) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChangeRejectedException) {
        throw ChangeRejectedException(
            CONFLICT,
            "[${by.key}] is staged by change ${held.id.value}, which could not be preempted: ${e.message}",
            e
        )
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "change ${held.id.value} could not be preempted", e)
    }

    /**
     * [conflicted] was made `CONFLICTED` for [by], which [taken] its slot or gave up: its candidate withdrawn, and
     * recorded as a failure that says which.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun preempted(conflicted: ChangeRequest, by: ChangeRequest, operator: Operator, taken: Boolean) {
        val note = if (taken) "preempted by ${by.id.value}" else "conflicted by ${by.id.value}, which did not take the slot"
        try {
            slots.withdraw(conflicted)
            flow.audit(ChangeEvent.Kind.FAILED, conflicted.copy(note = note), operator.actor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The holder is CONFLICTED already, so it publishes nothing: only the withdrawal or its record failed.
            log.warn(e) { "Change ${conflicted.id.value} lost its slot to ${by.id.value}, unrecorded." }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger(Changes::class.java.name)
    }
}
