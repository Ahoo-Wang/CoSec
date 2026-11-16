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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.Approval
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.APPROVAL
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.BREAK_GLASS
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.CONFLICT
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.FORBIDDEN
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.OPEN
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.ProvenanceKind
import java.time.Clock

/**
 * Approving, rejecting and abandoning a change (RFC 0007 §7.5): who may, with what recorded first, and what the
 * change and its slot become. [Changes.approve], [Changes.reject] and [Changes.abandon] are its entries.
 */
internal class Approvals(
    private val flow: ChangeLifecycle,
    private val revisions: Revisions,
    private val clock: Clock,
    private val authority: ChangeAuthority,
    private val slots: StagingSlots,
    private val listeners: ChangeListeners,
    private val preemption: Preemption,
) {
    /**
     * Approves an `AWAITING_APPROVAL` change (RFC 0007 §7.5): [operator] holds `cosec:approve` on the document and is
     * not its author, and approves the [candidate] digest it has seen; a change whose candidate is another is a 409.
     * The approval covers the change's escalations, and the change is `READY`.
     *
     * An approval is a person's ([ChangeAuthority.approvalRefusal]): an API key never approves, and only a platform
     * principal approves a boundary change. A break-glass approval needs a REQUIRED change listener, and is recorded
     * there once the digest is checked, before the approval is (RC1-30).
     */
    suspend fun approve(operator: Operator, id: ChangeId, candidate: String): ChangeRequest {
        val change = flow.visible(operator, id)
        authority.require(operator, change.key, ControlActions.APPROVE)
        PublishRules.requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        authority.requireApprovable(operator, change, candidate)
        val breakGlass = change.escalations.any { it.reason == BREAK_GLASS }
        if (breakGlass) {
            PublishRules.requireAudit(true, listeners.hasRequired)
            flow.audit(ChangeEvent.Kind.BREAK_GLASS_APPROVED, change, operator.actor)
        }
        return flow.transition(id, setOf(ChangeState.AWAITING_APPROVAL)) { current ->
            if (current.candidate != candidate) {
                throw ChangeRejectedException(CONFLICT, "change ${id.value} is not candidate $candidate")
            }
            // Who approved, as they were: publish checks again that they still hold cosec:approve (review K3).
            val approval = Approval(
                operator.actor,
                clock.instant(),
                candidate,
                current.escalations.map { it.subject },
                operator.memberships,
                // Every attribute, of every type (round-2 review R2, RC1-2).
                operator.attributes,
                principalBindings = operator.principalBindings,
                breakGlass = operator.breakGlass,
                credentialId = operator.security?.principal?.credential?.id,
                issuedAt = operator.security?.principal?.issuedAt,
            )
            current.copy(
                state = ChangeState.READY,
                approval = approval,
                findings = current.findings.filterNot {
                    it.check == APPROVAL
                }
            )
        }.also { if (!breakGlass) flow.audit(ChangeEvent.Kind.APPROVED, it, operator.actor) }
    }

    /**
     * Rejects an `AWAITING_APPROVAL` change: [operator] holds `cosec:approve` on the document, and is a platform
     * principal when the change sets a boundary (`BDY-2`). Its slot is released.
     */
    suspend fun reject(operator: Operator, id: ChangeId, reason: String): ChangeRequest {
        requireRequest(reason.isNotBlank()) { "A rejection needs a reason." }
        val change = flow.visible(operator, id)
        authority.require(operator, change.key, ControlActions.APPROVE)
        authority.requireBoundaryOperator(operator, change)
        PublishRules.requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        val rejected = flow.transition(id, setOf(ChangeState.AWAITING_APPROVAL)) {
            it.copy(state = ChangeState.REJECTED, findings = it.findings + Finding(APPROVAL, "$", reason, FORBIDDEN))
        }
        slots.release(rejected)
        flow.audit(ChangeEvent.Kind.REJECTED, rejected, operator.actor)
        return rejected
    }

    /**
     * Abandons a change that is not finished; its slot is released. Its author may, and so may whoever holds
     * `cosec:publish` on the document, which the change may be holding up; a change that sets a boundary, only a
     * platform principal (`BDY-2`). A revoke of a break-glass binding that still grants, only its author (RC1-51): the
     * principal it revokes holds `cosec:publish` through that binding, and nobody else needs to, since a new revoke
     * takes its slot (`BRK-9`).
     *
     * Every abandon is recorded ([ChangeEvent.Kind.ABANDONED], its note naming the change's author). A break-glass
     * change's needs a REQUIRED change listener and is recorded there before it is stored, as its approval is (RC1-30);
     * any other change's is recorded after, best effort.
     */
    suspend fun abandon(operator: Operator, id: ChangeId): ChangeRequest {
        val current = flow.visible(operator, id)
        authority.requireBoundaryOperator(operator, current)
        if (operator.actor != current.author) {
            authority.require(operator, current.key, ControlActions.PUBLISH)
            if (preemption.revokesBreakGlass(current, revisions.head(current.key))) {
                throw ChangeRejectedException(FORBIDDEN, "only its author abandons change ${id.value}, a live revoke")
            }
        }
        val breakGlass = current.kind == ProvenanceKind.BREAK_GLASS
        if (breakGlass) {
            PublishRules.requireState(current, OPEN)
            PublishRules.requireAudit(true, listeners.hasRequired)
            flow.audit(ChangeEvent.Kind.ABANDONED, current.abandoned(), operator.actor, gated = true)
        }
        val change = flow.transition(id, OPEN) { it.copy(state = ChangeState.ABANDONED) }
        slots.release(change)
        if (!breakGlass) {
            flow.audit(ChangeEvent.Kind.ABANDONED, change.abandoned(), operator.actor)
        }
        return change
    }
}
