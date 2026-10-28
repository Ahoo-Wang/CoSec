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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.document.ProvenanceKind
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
    /** Proposes the binding and stages it, so that it waits for its approval. */
    suspend fun request(
        operator: Operator,
        principal: PrincipalId,
        expiresAt: Instant,
        reason: String,
        policy: PolicyId = BuiltinPolicies.ROOT,
        binding: BindingId = BindingId("break-glass-${principal.value}-${expiresAt.toEpochMilli()}"),
    ): ChangeRequest {
        require(reason.isNotBlank()) { "Break-glass needs a reason." }
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

    /** Removes break-glass binding [binding] and publishes the removal: one person, no window, no approval. */
    suspend fun revoke(operator: Operator, binding: BindingId, reason: String): ChangeRequest {
        require(reason.isNotBlank()) { "Revoking break-glass needs a reason." }
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

    private suspend fun head(): Long = revisions.head(SnapshotKey.Platform)?.revision ?: 0
}
