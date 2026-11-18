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
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

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
        // Not a variant of [base]: a document of [policy] alone, under base's header, so every other member is dropped.
        base.copy(
            policies = listOf(policy),
            bindings = emptyList(),
            catalog = null,
            boundary = null,
            provenance = null,
            limits = null,
            attributes = null,
            rateLimits = emptyList(),
            roles = emptyList(),
        )
    ).decodeToString()
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
