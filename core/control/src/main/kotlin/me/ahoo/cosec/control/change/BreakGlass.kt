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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/**
 * Break-glass through the control plane (RFC 0007 §8), the only way to create it:
 *
 * - [request]: a platform principal holding `cosec:breakglass` asks for a break-glass binding of a platform principal
 *   (itself or another) to a platform policy (`cosec:root` by default; a narrower emergency policy is better), expiring
 *   within `cosec.control.break-glass.max-duration`. The request is audited by a REQUIRED change listener before it is
 *   recorded, has no shadow window, and waits for a second platform principal holding `cosec:approve` ([Changes.approve]).
 *   Publishing ([Changes.publish]) checks the expiry again, and is audited before the head moves.
 * - [revoke]: any platform principal holding `cosec:breakglass` removes one, alone, at once.
 *
 * There is no renewal: a new request is approved again. An expired binding stops applying by itself (RFC 0004 §4.2).
 */
class BreakGlass(private val changes: Changes, private val revisions: Revisions) {
    /**
     * Proposes the binding and stages it, so that it waits for its approval. Its id is derived from [principal] and
     * [expiresAt], never the caller's: a request never replaces another binding (RC1-29), and one whose id is already
     * taken is refused (409).
     */
    suspend fun request(
        operator: Operator,
        principal: PrincipalId,
        expiresAt: Instant,
        reason: String,
        policy: PolicyId = BuiltinPolicies.ROOT,
    ): ChangeRequest {
        require(reason.isNotBlank()) { "Break-glass needs a reason." }
        val binding = BindingId("break-glass-${principal.value}-${expiresAt.toEpochMilli()}")
        if (revisions.head(SnapshotKey.Platform)?.document?.bindings?.any { it.id == binding } == true) {
            throw ChangeRejectedException(CONFLICT, "the platform document already has a binding [${binding.value}]")
        }
        val grant = Binding(
            binding,
            SubjectRef.Principal(TenantId.PLATFORM, principal),
            BindingScope(Match.Any, Match.Any),
            policy,
            expiresAt = expiresAt.toEpochMilli(),
            breakGlass = true,
        )
        val proposed = changes.propose(
            operator,
            SnapshotKey.Platform,
            head(),
            listOf(Operation.PutBinding(grant)),
            reason,
            ProvenanceKind.BREAK_GLASS
        )
        if (proposed.state != ChangeState.CHECKED) {
            return proposed
        }
        return changes.stage(operator, proposed.id)
    }

    /**
     * Removes break-glass binding [binding] and publishes the removal: one person, no window, no approval. A revoke of
     * it that holds the slot `READY`, its publish stopped (its REQUIRED listener's store was down, say), is published
     * instead (review N1); one [operator] may not see or publish is taken over by a new revoke, which preempts it.
     */
    suspend fun revoke(operator: Operator, binding: BindingId, reason: String): ChangeRequest {
        require(reason.isNotBlank()) { "Revoking break-glass needs a reason." }
        stalled(operator, binding)?.let { return it }
        val proposed = changes.propose(
            operator,
            SnapshotKey.Platform,
            head(),
            listOf(Operation.RemoveBinding(binding)),
            reason,
            ProvenanceKind.BREAK_GLASS
        )
        if (proposed.state != ChangeState.CHECKED) {
            return proposed
        }
        val staged = changes.stage(operator, proposed.id)
        if (staged.state != ChangeState.READY) {
            return staged
        }
        return changes.publish(operator, staged.id)
    }

    /** The stalled revoke of [binding], published by [operator]; `null` when there is none, or it does not publish. */
    private suspend fun stalled(operator: Operator, binding: BindingId): ChangeRequest? {
        val held = changes.stagedOn(SnapshotKey.Platform)?.takeIf { held ->
            held.state == ChangeState.READY && PublishRules.revokes(held) &&
                held.operations.any { it is Operation.RemoveBinding && it.id == binding }
        } ?: return null
        val published = try {
            changes.publish(operator, held.id)
        } catch (e: ChangeRejectedException) {
            if (e.status != NOT_FOUND && e.status != CONFLICT) throw e
            return null
        }
        return published.takeIf { it.state == ChangeState.PUBLISHED }
    }

    private suspend fun head(): Long = revisions.head(SnapshotKey.Platform)?.revision ?: 0

    private companion object {
        const val CONFLICT = 409
        const val NOT_FOUND = 404
    }
}
