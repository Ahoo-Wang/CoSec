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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId

/** What a change grants (§7.4.1): the fragments, one per action and resource of each ALLOW a binding or an attachment reaches. */
internal class GrantFragmenter(private val narrowing: GrantNarrowing, private val equality: GrantEquality) {
    /**
     * The fragments [change] grants (§7.4.1). Policies resolve in the candidate; a new or changed binding whose policy
     * does not resolve is an [unresolved][GrantFragment.unresolved] fragment (RC1-32).
     *
     * Removing one of the tenant's own policies grants too when another document defines its id (RC1-9): the
     * bindings and attachments that named the tenant's now name that one, which nobody analyzed for them.
     */
    fun fragments(
        snapshot: GrantSnapshot,
        change: GrantChange,
        budget: ComparisonBudget = ComparisonBudget(),
    ): List<GrantFragment> {
        val before = snapshot.tenant(change.tenant)
        val after = change.candidate
        val fragments = ArrayList<GrantFragment>()
        // By id: a few roles can derive as many bindings as a whole document can hold written out.
        val previous = before.bindings.groupBy { it.id }
        val changed = after.bindings.filter { binding ->
            previous[binding.id].orEmpty().none { GrantEquality.same(it, binding) }
        }
        for (binding in changed) {
            val policy = resolve(snapshot, after, binding)
            if (policy == null) {
                fragments += GrantFragment.unresolved(change.tenant, binding)
                continue
            }
            policy.statements.indices.forEach { split(change.tenant, policy, it, Grantee.Bound(binding), fragments) }
        }
        // Looked up once per binding, not once per policy and binding: a document of n of each is n, not n².
        val changedSet = changed.toHashSet()
        val bound = after.bindings.groupBy { it.policy }
        reresolved(snapshot, change, changedSet, fragments)
        for (policy in after.policies) {
            changedPolicy(
                snapshot,
                change,
                before.policy(policy.id),
                policy,
                bound[policy.id].orEmpty(),
                changedSet,
                fragments,
                budget,
            )
        }
        return fragments
    }

    /**
     * What re-resolves when the candidate removes one of the tenant's own policies whose id another document defines
     * (RC1-9): every live binding left unchanged that named it grants all of the policy it names now, and so does
     * every attachment of it, when that one is attachable. Nothing is granted when the other is the same policy.
     */
    private fun reresolved(
        snapshot: GrantSnapshot,
        change: GrantChange,
        changed: Set<Binding>,
        into: MutableList<GrantFragment>,
    ) {
        val after = change.candidate
        val before = snapshot.tenant(change.tenant)
        val removed = before.policies.filter { after.policy(it.id) == null }.associateBy { it.id }
        if (removed.isEmpty()) {
            return
        }
        val live = after.bindings.filter { binding ->
            binding.policy in removed && binding !in changed && binding.expiresAt.let { it == null || snapshot.now < it }
        }
        for (binding in live) {
            val now = resolve(snapshot, after, binding)
            when {
                // It names nothing now: what it grants cannot be told (RC1-32).
                now == null -> into += GrantFragment.unresolved(change.tenant, binding)
                !equality.samePolicy(removed.getValue(binding.policy), now) ->
                    now.statements.indices.forEach { split(change.tenant, now, it, Grantee.Bound(binding), into) }
            }
        }
        for (own in removed.values) {
            val shared = snapshot.platform.policy(own.id)?.takeIf { it.attachable }
            if (shared != null && !equality.samePolicy(own, shared)) {
                shared.statements.indices.forEach { split(change.tenant, shared, it, Grantee.Attaching, into) }
            }
        }
    }

    /**
     * The ALLOWs of each policy of [after] that are new or widen what it had in [before] (§7.4.1, §7.4.4): what every
     * binding of that policy is granted anew, wherever it is. A policy that only narrows is not listed.
     */
    fun widened(
        before: GrantDocument,
        after: GrantDocument,
        budget: ComparisonBudget = ComparisonBudget(),
    ): Map<PolicyId, List<Int>> =
        after.policies.associate { it.id to granted(before.policy(it.id), it, budget) }.filterValues { it.isNotEmpty() }

    /**
     * The indexes of the ALLOWs of [policy] that narrow none of [previous]'s: none when [policy] is [previous] again
     * (R2C-25, `CTL-50`), which [GrantEquality.samePolicy] tells in one pass where comparing statement by statement costs
     * the square of their number.
     *
     * The skip holds because what this answers depends on the two versions only, and identity holds in every member
     * of them the grant depends on (`GrantPolicyMembersTest`):
     * - **Bindings.** It says what the bindings that already pointed to the policy are granted anew; a binding that is
     *   new or changed is granted all of its policy by [fragments] whatever this answers, so an unchanged policy newly
     *   bound is still analysed where it is bound.
     * - **Roles and short forms.** Both versions are read written out ([GrantDocuments.of], `RoleExpansion.effective`):
     *   a role or a catalog entry that now expands differently is a different policy or a different binding here.
     * - **Attachments.** `attachable` is compared, and an unchanged attachable policy grants attachers nothing new.
     * - **The platform document and the boundary.** Neither is a version of a tenant's policy: the boundary is left
     *   out of [GrantDocuments.of] (setting one is an escalation of its own), and what the platform's or an app's
     *   documents hold changes what the grantor holds (the verdicts), never what a tenant's unchanged policy grants.
     *   A tenant policy removed while another document defines its id is [reresolved], not this.
     */
    private fun granted(previous: GrantPolicy?, policy: GrantPolicy, budget: ComparisonBudget): List<Int> {
        if (previous != null && equality.samePolicy(previous, policy)) {
            return emptyList()
        }
        val twins = previous?.let(GrantNarrowing::Twins)
        return policy.statements.indices.filter {
            val statement = policy.statements[it]
            statement.effect == Effect.ALLOW &&
                (previous == null || !narrowing.narrowsAny(policy, statement, previous, twins, budget))
        }
    }

    /**
     * The fragments a new or changed [policy] grants: its ALLOWs that narrow none of [previous]'s, to every binding
     * that already points to it (the [changed] ones grant all of it anyway), and to attachments if it is attachable.
     */
    private fun changedPolicy(
        snapshot: GrantSnapshot,
        change: GrantChange,
        previous: GrantPolicy?,
        policy: GrantPolicy,
        /** The candidate's bindings to [policy], in the candidate's order. */
        boundTo: List<Binding>,
        changed: Set<Binding>,
        into: MutableList<GrantFragment>,
        budget: ComparisonBudget,
    ) {
        val granted = granted(previous, policy, budget)
        val bound = boundTo.filter { binding ->
            binding !in changed && binding.expiresAt.let { it == null || snapshot.now < it }
        }
        for (binding in bound) {
            granted.forEach { split(change.tenant, policy, it, Grantee.Bound(binding), into) }
        }
        if (policy.attachable) {
            val attached = if (previous?.attachable == true) granted else policy.statements.indices.toList()
            attached.forEach { split(change.tenant, policy, it, Grantee.Attaching, into) }
        }
    }

    /** One fragment per action and resource of an ALLOW statement (§7.4.1). */
    private fun split(
        tenant: TenantId,
        policy: GrantPolicy,
        index: Int,
        grantee: Grantee,
        into: MutableList<GrantFragment>,
    ) {
        val statement = policy.statements[index]
        if (statement.effect != Effect.ALLOW) {
            return
        }
        val scope = when (grantee) {
            is Grantee.Bound -> ScopeSet.of(grantee.binding.scope.app, grantee.binding.scope.space)
            Grantee.Attaching -> ScopeSet.EVERY
        }
        val condition = both(policy.condition, statement.condition)
        for (action in statement.actions) {
            for (resource in statement.resources) {
                into += GrantFragment(tenant, policy.id, index, action, resource, condition, grantee, scope)
            }
        }
    }

    /** A binding's policy in the candidate: the tenant's own, the platform's, or its app's (RFC 0007 §3.1 N1). */
    private fun resolve(snapshot: GrantSnapshot, candidate: GrantDocument, binding: Binding): GrantPolicy? {
        candidate.policy(binding.policy)?.let { return it }
        snapshot.platform.policy(binding.policy)?.let { return it }
        val app = binding.scope.app
        return if (app is Match.Exactly) snapshot.apps[app.value]?.policy(binding.policy) else null
    }
}
