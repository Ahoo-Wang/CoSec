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
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock

/**
 * Whether a change may take a staging slot another change holds (RFC 0007 §5.5, RC1-12): a break-glass revoke always
 * may; otherwise a change that only narrows ([Narrowing]), on the current head, staged by a holder of
 * `cosec:skip-shadow` on the document. An emergency ban must not queue behind a change in its shadow window.
 */
@OptIn(InternalCoSecApi::class)
internal class Preemption(
    private val revisions: Revisions,
    private val authority: ChangeAuthority,
    private val clock: Clock,
) {
    suspend fun preempts(operator: Operator, change: ChangeRequest, head: Head?): Boolean {
        if (head?.digest != change.base.digest) {
            return false
        }
        if (revokesBreakGlass(change, head)) {
            return true
        }
        if (!authority.holds(operator, change.key, ControlActions.SKIP_SHADOW)) {
            return false
        }
        val candidate = Operations.apply(change.key, head?.document, change.operations).candidate ?: return false
        return Narrowing.only(
            head?.document,
            candidate,
            change.operations,
            policyOf = { policy -> elsewhere(change.key, policy) },
            shadows = { policy -> change.key is SnapshotKey.Tenant && defines(head, candidate, policy) && platform(policy) != null },
        )
    }

    /** Whether [change] only revokes break-glass bindings [head] has that still grant: an expired one grants nothing. */
    fun revokesBreakGlass(change: ChangeRequest, head: Head?): Boolean {
        val now = clock.millis()
        val glass = head?.document?.bindings.orEmpty()
            .filter { it.breakGlass && (it.expiresAt?.let { expiresAt -> expiresAt > now } ?: true) }
            .map { it.id }
            .toSet()
        return change.kind == ProvenanceKind.BREAK_GLASS && change.operations.isNotEmpty() &&
            change.operations.all { it is Operation.RemoveBinding && it.id in glass }
    }

    /** Binding [id] of [head] while it grants: there, and not expired. */
    fun granting(head: Head?, id: BindingId): Binding? {
        val now = clock.millis()
        return head?.document?.bindings?.firstOrNull { it.id == id && (it.expiresAt ?: Long.MAX_VALUE) > now }
    }

    /** Whether [head] or [candidate], the tenant's own document, defines [policy]. */
    private fun defines(head: Head?, candidate: PolicyDocument, policy: PolicyId): Boolean =
        head?.document?.policies.orEmpty().any { it.id == policy } || candidate.policies.any { it.id == policy }

    private suspend fun platform(policy: PolicyId): PolicyDefinition? =
        revisions.head(SnapshotKey.Platform)?.document?.policies?.firstOrNull { it.id == policy }

    private suspend fun elsewhere(key: SnapshotKey, policy: PolicyId): PolicyDefinition? =
        policyElsewhere(key, policy) { revisions.head(it)?.document }
}
