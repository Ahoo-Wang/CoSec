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
import me.ahoo.cosec.control.audit.ChangeAuditException
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.audit.MovedHead
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.CONFLICT
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.MAX_ATTEMPTS
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.NOT_FOUND
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.UNAVAILABLE
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The life of a change request (RFC 0007 §6.1) that every command of [Changes] goes through: reading a change as an
 * operator may see it, expiring it, moving it from one state to the next with a conditional write ([transition]),
 * settling it, and telling the listeners of each step. Where the change flow's transition table is to be.
 */
internal class ChangeLifecycle(
    private val store: ControlStore,
    private val clock: Clock,
    private val options: ChangeOptions,
    private val slots: StagingSlots,
    private val listeners: ChangeListeners,
    private val authority: ChangeAuthority,
) {
    /**
     * The change [id] as [operator] may see it (review K2): one that does not exist, is of a document the hard rule
     * keeps them from, or that they neither wrote nor may `cosec:read`, is the same 404, before its state is looked at
     * or expired.
     *
     * Whether they may read it is decided once, whatever the change is: on its document when the hard rule lets them
     * act on it, on their own tenant's document (the platform's, for the platform) otherwise, so that a missing change
     * costs one authorization too. When that decision is indeterminate, or the change cannot be read, the answer is a
     * 503, never a 404 (round-2 review R6): "cannot tell" is not "does not exist", and, the same for every change, it
     * tells nothing of which exist. What the decision cannot equalize is the store read of a change that exists.
     */
    suspend fun visible(operator: Operator, id: ChangeId): ChangeRequest {
        val reachable = stored(id)?.takeIf { TenantIsolation.allows(operator, it.key) }
        if (reachable != null && reachable.author == operator.actor) {
            return expired(reachable)
        }
        val key = reachable?.key ?: if (operator.platform) SnapshotKey.Platform else SnapshotKey.Tenant(operator.tenant.value)
        val decision = authority.decide(operator, key, ControlActions.READ)
        if (decision == ControlDecision.PERMIT && reachable != null) {
            return expired(reachable)
        }
        throw if (decision == ControlDecision.INDETERMINATE) {
            ChangeRejectedException(UNAVAILABLE, "whether change ${id.value} may be read could not be decided")
        } else {
            ChangeRejectedException(NOT_FOUND, "no change ${id.value}")
        }
    }

    /**
     * The stored change [id]; a store that cannot be read, or a record that does not decode, is a 503, never "no change".
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun stored(id: ChangeId): ChangeRequest? = try {
        store.change(id)?.value
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "change ${id.value} could not be read", e)
    }

    /** The change holding [key]'s staging slot, `EXPIRED` once past its expiry; `null` when none does. */
    suspend fun stagedOn(key: SnapshotKey): ChangeRequest? =
        store.stage(key)?.value?.holder?.let { stored(it) }?.let { expired(it) }

    /** The change [id]; one past its expiry is `EXPIRED` from then on. */
    suspend fun get(id: ChangeId): ChangeRequest = expired(read(id).value)

    /** [change], as [get] answers for it, when the caller has just read it from the store and need not read it again. */
    suspend fun current(change: ChangeRequest): ChangeRequest = expired(change)

    /**
     * Tells the listeners (RFC 0007 §10.5); a REQUIRED one that cannot record a [gated] step stops it (503). A step is
     * gated by its event's kind, or by what it does to its change (a break-glass change's abandon, RC1-51).
     */
    suspend fun audit(
        kind: ChangeEvent.Kind,
        change: ChangeRequest,
        operator: Actor,
        moved: List<MovedHead> = emptyList(),
        gated: Boolean = kind.gated,
    ) {
        try {
            listeners.publish(change.event(kind, operator, clock, moved), gated)
        } catch (e: ChangeAuditException) {
            throw ChangeRejectedException(UNAVAILABLE, e.message.orEmpty(), e)
        }
    }

    /** Moves [change] to a final or retry [state] whatever it is now, records it, and releases the slot. */
    suspend fun settle(
        change: ChangeRequest,
        state: ChangeState,
        findings: List<Finding> = change.findings,
        published: Long? = null,
        from: Set<ChangeState> = setOf(ChangeState.READY),
    ): ChangeRequest {
        // Once the head is written the change is published, whatever happened to it meanwhile; otherwise only a
        // change still in [from] moves.
        val settled = transition(change.id, if (state == ChangeState.PUBLISHED) ChangeState.entries.toSet() else from) {
            it.copy(state = state, findings = findings, publishedRevision = published ?: it.publishedRevision)
        }
        slots.release(settled)
        return settled
    }

    suspend fun read(id: ChangeId) =
        store.change(id) ?: throw ChangeRejectedException(NOT_FOUND, "no change ${id.value}")

    /** [change], `EXPIRED` once it is past [ChangeOptions.expireAfter] and not finished. */
    suspend fun expired(change: ChangeRequest): ChangeRequest {
        if (change.state.terminal || !clock.instant().isAfter(change.createdAt.plus(options.expireAfter))) {
            return change
        }
        val expired = transition(change.id, setOf(change.state)) { it.copy(state = ChangeState.EXPIRED) }
        slots.release(expired)
        return expired
    }

    /** A conditional write of [change] from one of [from] to what [next] makes of it; retried while the change moves. */
    suspend fun transition(
        id: ChangeId,
        from: Set<ChangeState>,
        next: suspend (ChangeRequest) -> ChangeRequest
    ): ChangeRequest {
        repeat(MAX_ATTEMPTS) {
            val current = read(id)
            PublishRules.requireState(current.value, from)
            val updated = next(current.value).copy(updatedAt = clock.instant())
            if (store.putChange(current.digest, updated)) {
                return updated
            }
        }
        throw ChangeRejectedException(CONFLICT, "change ${id.value} kept changing")
    }

    internal companion object {
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val GONE = 410
        const val UNPROCESSABLE = 422
        const val PRECONDITION_REQUIRED = 428
        const val UNAVAILABLE = 503
        const val BREAK_GLASS = "BREAK_GLASS"
        const val BREAK_GLASS_EXEMPT = "break-glass has no shadow window (RFC 0007 Q12)"
        const val MAX_ATTEMPTS = 16
        const val APPROVAL = "APPROVAL"
        val REBASABLE = setOf(ChangeState.CONFLICTED, ChangeState.DRAFT, ChangeState.CHECKED)
        val STAGED = ChangeState.entries.filter { it.staged }.toSet()
        val OPEN = ChangeState.entries.filterNot { it.terminal }.toSet()
        val UNPUBLISHED = ChangeState.entries.toSet() - ChangeState.PUBLISHED
        val LOST = setOf(ChangeState.READY, ChangeState.CONFLICTED)
    }
}
