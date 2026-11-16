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
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition

/** Who a fragment is granted to (RFC 0007 §7.4.1). */
@InternalCoSecApi
sealed interface Grantee {
    /** The subject of a binding, in its scope. */
    @InternalCoSecApi
    class Bound(val binding: Binding) : Grantee {
        override fun toString(): String = "binding ${binding.id.value}"
    }

    /** Any authenticated principal of the tenant that attaches the policy, in `(*, *)`. */
    @InternalCoSecApi
    object Attaching : Grantee {
        override fun toString(): String = "attachment"
    }
}

/**
 * One granted statement fragment (RFC 0007 §7.4.1): one action and one resource of an ALLOW statement, with the
 * statement's condition and its policy's, granted to [grantee] in [scope] of [tenant].
 *
 * A binding whose policy does not resolve is one fragment of every action and resource that is not [resolved]: what
 * it grants cannot be told, so it is never contained (RC1-32).
 */
@InternalCoSecApi
class GrantFragment(
    val tenant: TenantId,
    val policy: PolicyId,
    /** The statement's index in the policy; -1 when the policy does not resolve. */
    val statement: Int,
    val action: ActionPattern,
    val resource: ResourcePattern,
    val condition: Condition?,
    val grantee: Grantee,
    val scope: ScopeSet,
    /** Whether [policy] resolved: `false` for the fragment of a binding whose policy does not (RC1-32). */
    val resolved: Boolean = true,
) {
    /** When the grant ends (epoch milliseconds): its binding's `expiresAt`; `null` for one that does not end. */
    val expiresAt: Long? get() = (grantee as? Grantee.Bound)?.binding?.expiresAt

    override fun toString(): String = if (resolved) {
        "${policy.value}[$statement] $action on $resource to $grantee in $scope"
    } else {
        "${policy.value} (does not resolve) to $grantee in $scope"
    }

    @InternalCoSecApi
    companion object {
        /** The fragment of [binding] of [tenant], whose policy does not resolve (RC1-32). */
        fun unresolved(tenant: TenantId, binding: Binding): GrantFragment = GrantFragment(
            tenant = tenant,
            policy = binding.policy,
            statement = -1,
            action = ActionPattern.ANY,
            resource = ResourcePattern.AnyResource,
            condition = null,
            grantee = Grantee.Bound(binding),
            scope = ScopeSet.of(binding.scope.app, binding.scope.space),
            resolved = false,
        )
    }
}
