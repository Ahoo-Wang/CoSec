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
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.document.RoleExpansion
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock

/**
 * Whether a change only narrows what its document grants (RFC 0007 §5.5, RC1-12), so that a holder of
 * `cosec:skip-shadow` may take the document's staging slot from a change that holds it: an emergency ban must not
 * queue. A whitelist, every operation one of:
 *
 * 1. a `RemoveBinding` whose binding, in the base, binds a policy of ALLOWs only (removing a DENY widens);
 * 2. a `PutPolicy` that only makes an attachable policy of ALLOWs only not attachable, its statements as they were
 *    (a DENY no longer attachable widens);
 * 3. a `PutPolicy` of a new id whose statements are all DENYs, or a `PutBinding` of a new id binding a policy of the
 *    candidate whose statements are all DENYs.
 *
 * Anything else, replacing an entry the base has in particular, does not narrow for this purpose, though it may; nor
 * does an operation on a policy id the document defines that another also defines ([shadows]: the platform's, for a
 * tenant document), since which definition a binding then reaches is not this document's alone. A policy a binding
 * names in another document is read through [policyOf]; one that cannot be found does not narrow. Blocking a principal
 * is no document change: the blacklist is an attribute provider's (`environment.blocked`), and waits for no slot.
 */
internal object Narrowing {
    suspend fun only(
        base: PolicyDocument?,
        candidate: PolicyDocument,
        operations: List<Operation>,
        shadows: suspend (PolicyId) -> Boolean = { false },
        policyOf: suspend (PolicyId) -> PolicyDefinition?,
    ): Boolean {
        if (base == null || operations.isEmpty()) {
            return false
        }
        return operations.all { narrows(it, base, candidate, shadows, policyOf) }
    }

    /**
     * Each DENY [base] has that [candidate] no longer has (RFC 0007 §7.4.4, amended on 2026-11-09; `CTL-31`): a policy
     * of [base] holding one that [candidate] does not define, and a binding of [base] that [candidate] does not have
     * whose policy holds one. A policy holds a DENY unless [allowsOnly] says otherwise, so one that cannot be found holds
     * one. Both documents are read expanded ([RoleExpansion.effective]): a role removed, or put with fewer permissions,
     * counts for each binding it no longer derives, and a short-form permission for the policy it no longer writes out.
     * A break-glass binding does not count: revoking one ends an emergency, which RFC 0007 §8 leaves to one person.
     */
    suspend fun removedDenies(
        base: PolicyDocument,
        candidate: PolicyDocument,
        policyOf: suspend (PolicyId) -> PolicyDefinition?,
    ): List<String> {
        val before = RoleExpansion.effective(base)
        val after = RoleExpansion.effective(candidate)
        val policies = after.policies.mapTo(HashSet()) { it.id }
        val bindings = after.bindings.mapTo(HashSet()) { it.id }
        val removed = ArrayList<String>()
        before.policies.filter { it.id !in policies && it.statements.any { s -> s.effect == Effect.DENY } }
            .mapTo(removed) { "policy ${it.id.value}" }
        for (binding in before.bindings) {
            if (binding.id !in bindings && !binding.breakGlass && !allowsOnly(base, binding.policy, policyOf, before)) {
                removed += "binding ${binding.id.value}"
            }
        }
        return removed
    }

    /** Whether [operation] is one of the whitelist's on [base], giving [candidate]. */
    private suspend fun narrows(
        operation: Operation,
        base: PolicyDocument,
        candidate: PolicyDocument,
        shadows: suspend (PolicyId) -> Boolean,
        policyOf: suspend (PolicyId) -> PolicyDefinition?,
    ): Boolean = when (operation) {
        is Operation.RemoveBinding -> {
            val removed = base.bindings.firstOrNull { it.id == operation.id }
            removed != null && !shadows(removed.policy) && allowsOnly(base, removed.policy, policyOf)
        }

        is Operation.PutPolicy -> !shadows(operation.policy.id) &&
            (
                madeUnattachable(base, operation.policy) ||
                    (base.policies.none { it.id == operation.policy.id } && deniesOnly(operation.policy))
                )

        is Operation.PutBinding -> {
            val binding = operation.binding
            base.bindings.none { it.id == binding.id } && !binding.breakGlass && !shadows(binding.policy) &&
                candidate.policies.firstOrNull { it.id == binding.policy }?.let(::deniesOnly) == true
        }

        else -> false
    }

    /**
     * Whether [policy], as [base] or another document defines it, is ALLOWs only and not the blacklist; [effective] is
     * [base] expanded, when the caller has it.
     */
    private suspend fun allowsOnly(
        base: PolicyDocument,
        policy: PolicyId,
        policyOf: suspend (PolicyId) -> PolicyDefinition?,
        effective: PolicyDocument? = null,
    ): Boolean {
        val statements = when (policy) {
            BuiltinPolicies.BLACKLIST -> return false
            BuiltinPolicies.ROOT -> return true
            else -> (effective ?: RoleExpansion.effective(base)).policies.firstOrNull { it.id == policy }?.statements
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

/**
 * [policy] as another document than [key]'s defines it, read through [documentOf]: the platform's, or the app's of a
 * permission policy, expanded.
 */
internal suspend fun policyElsewhere(
    key: SnapshotKey,
    policy: PolicyId,
    documentOf: suspend (SnapshotKey) -> PolicyDocument?,
): PolicyDefinition? {
    if (key != SnapshotKey.Platform) {
        documentOf(SnapshotKey.Platform)?.policies?.firstOrNull { it.id == policy }?.let { return it }
    }
    val prefix = PolicyDocumentCompiler.PERMISSION_PREFIX
    val app = policy.value.removePrefix(prefix).substringBefore('/')
    if (!policy.value.startsWith(prefix) || !SnapshotKey.App.isValidAppId(app)) {
        return null
    }
    return documentOf(SnapshotKey.App(app))?.let(RoleExpansion::effective)?.policies?.firstOrNull { it.id == policy }
}
