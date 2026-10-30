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

import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Whether a change only narrows what its document grants (RFC 0007 §5.5, RC1-12), so that a holder of
 * `cosec:skip-shadow` may take the document's staging slot from a change that holds it: an emergency ban must not
 * queue. A whitelist, every operation one of:
 *
 * 1. a `RemoveBinding` whose binding, in the base, binds a policy of ALLOWs only that is not `cosec:blacklist`
 *    (removing a DENY or a blacklist entry widens);
 * 2. a `PutPolicy` that only makes an attachable policy of ALLOWs only not attachable, its statements as they were
 *    (a DENY no longer attachable widens);
 * 3. a `PutBinding` of a new id binding `cosec:blacklist`;
 * 4. a `PutPolicy` of a new id whose statements are all DENYs, or a `PutBinding` of a new id binding a policy of the
 *    candidate whose statements are all DENYs.
 *
 * Anything else, replacing an entry the base has in particular, does not narrow for this purpose, though it may.
 * A policy a binding names in another document is read through [policyOf]; one that cannot be found does not narrow.
 */
internal object Narrowing {
    suspend fun only(
        base: PolicyDocument?,
        candidate: PolicyDocument,
        operations: List<Operation>,
        policyOf: suspend (PolicyId) -> PolicyDefinition?,
    ): Boolean {
        if (base == null || operations.isEmpty()) {
            return false
        }
        return operations.all { operation ->
            when (operation) {
                is Operation.RemoveBinding -> {
                    val removed = base.bindings.firstOrNull { it.id == operation.id }
                    removed != null && allowsOnly(base, removed.policy, policyOf)
                }

                is Operation.PutPolicy -> madeUnattachable(base, operation.policy) ||
                    (base.policies.none { it.id == operation.policy.id } && deniesOnly(operation.policy))

                is Operation.PutBinding -> {
                    val binding = operation.binding
                    base.bindings.none { it.id == binding.id } && !binding.breakGlass &&
                        (
                            binding.policy == BuiltinPolicies.BLACKLIST ||
                                candidate.policies.firstOrNull { it.id == binding.policy }?.let(::deniesOnly) == true
                            )
                }

                else -> false
            }
        }
    }

    /** Whether [policy], as [base] or another document defines it, is ALLOWs only and not the blacklist. */
    private suspend fun allowsOnly(
        base: PolicyDocument,
        policy: PolicyId,
        policyOf: suspend (PolicyId) -> PolicyDefinition?,
    ): Boolean {
        val statements = when (policy) {
            BuiltinPolicies.BLACKLIST -> return false
            BuiltinPolicies.ROOT -> return true
            else -> base.policies.firstOrNull { it.id == policy }?.statements
                ?: BuiltinPolicies.CONTROL[policy]
                ?: policyOf(policy)?.statements
        }
        return statements != null && statements.all { it.effect == Effect.ALLOW }
    }

    /** Whether [policy] only turns the attachable base policy of its id, of ALLOWs only, not attachable. */
    private fun madeUnattachable(base: PolicyDocument, policy: PolicyDefinition): Boolean {
        val before = base.policies.firstOrNull { it.id == policy.id } ?: return false
        return before.attachable && !policy.attachable &&
            before.statements.all { it.effect == Effect.ALLOW } &&
            encoded(base, before.unattachable()) == encoded(base, policy)
    }

    private fun deniesOnly(policy: PolicyDefinition): Boolean =
        !policy.boundary && policy.statements.isNotEmpty() && policy.statements.all { it.effect == Effect.DENY }

    private fun PolicyDefinition.unattachable() = PolicyDefinition(id, false, condition, statements, boundary)

    /** [policy] as the document format writes it, so that two definitions compare by what they say. */
    private fun encoded(base: PolicyDocument, policy: PolicyDefinition): String = PolicyDocumentCodec.encode(
        PolicyDocument(base.kind, base.owner, base.revision, base.publishedAt, listOf(policy), emptyList())
    ).decodeToString()
}

/**
 * Whether a change may take a staging slot another change holds (RFC 0007 §5.5, RC1-12): a break-glass revoke always
 * may; otherwise a change that only narrows ([Narrowing]), on the current head, staged by a holder of
 * `cosec:skip-shadow` on the document. An emergency ban must not queue behind a change in its shadow window.
 */
internal class Preemption(private val revisions: Revisions, private val authority: ChangeAuthority) {
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
        return Narrowing.only(head?.document, candidate, change.operations) { policy -> elsewhere(change.key, policy) }
    }

    private fun revokesBreakGlass(change: ChangeRequest, head: Head?): Boolean {
        val glass = head?.document?.bindings.orEmpty().filter { it.breakGlass }.map { it.id }.toSet()
        return change.kind == ProvenanceKind.BREAK_GLASS && change.operations.isNotEmpty() &&
            change.operations.all { it is Operation.RemoveBinding && it.id in glass }
    }

    /** [policy] as another document than [key]'s defines it: the platform's, or the app's of a permission policy. */
    private suspend fun elsewhere(key: SnapshotKey, policy: PolicyId): PolicyDefinition? {
        if (key != SnapshotKey.Platform) {
            revisions.head(SnapshotKey.Platform)?.document?.policies?.firstOrNull { it.id == policy }?.let { return it }
        }
        val prefix = PolicyDocumentCompiler.PERMISSION_PREFIX
        val app = policy.value.removePrefix(prefix).substringBefore('/')
        if (!policy.value.startsWith(prefix) || app.isEmpty()) {
            return null
        }
        return revisions.head(SnapshotKey.App(app))?.document?.policies?.firstOrNull { it.id == policy }
    }
}
