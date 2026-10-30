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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.decision.StatementRef

/** Who a fragment is granted to (RFC 0007 §7.4.1). */
@ExperimentalCoSecApi
sealed interface Grantee {
    /** The subject of a binding, in its scope. */
    class Bound(val binding: Binding) : Grantee {
        override fun toString(): String = "binding ${binding.id.value}"
    }

    /** Any authenticated principal of the tenant that attaches the policy, in `(*, *)`. */
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
@ExperimentalCoSecApi
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

/** Why a fragment is [GrantVerdict.Unknown]. */
@ExperimentalCoSecApi
enum class UnknownReason {
    /** The grantor is not in the tenant of the document. */
    CROSS_TENANT,

    /** No single ALLOW of the grantor is proven to contain the fragment. */
    NOT_COVERED,

    /** A DENY that binds the grantor (other than through `Anyone`) may overlap the fragment. */
    DENY_OVERLAP,

    /** The fragment's binding names a policy that does not resolve, so what it grants cannot be told (RC1-32). */
    UNRESOLVED,

    /** An app document the fragment reaches could not be read, so the grantor's rights there are not known (RC1-4). */
    UNREADABLE,
}

/** How far the closest of the grantor's ALLOWs got, in the order the analysis checks (RFC 0007 §7.4.3). */
@ExperimentalCoSecApi
enum class Shortfall {
    /** The grantor holds no ALLOW at all. */
    NO_ALLOW,

    /** The ALLOW does not apply to the grantor everywhere the fragment is granted. */
    SCOPE,
    ACTION,
    RESOURCE,

    /** The grantor's condition, specialized for the grantor, is not implied by the fragment's. */
    CONDITION,

    /**
     * The ALLOW contains the fragment, but the grantor holds it only until its binding expires, and the fragment's
     * grant lasts longer or does not end (RC1-3).
     */
    EXPIRY,
}

/** What the analysis concluded about one fragment. Only [Unknown] needs approval (RFC 0007 §7.5). */
@ExperimentalCoSecApi
sealed interface GrantVerdict {
    /** The grantor holds `cosec:approve` on the document: it is the trust root, and its changes are not analyzed. */
    object TrustRoot : GrantVerdict

    /** Contained in the grantor's statement [by]; `null` when the fragment can apply to no request at all. */
    class Contained(val by: StatementRef.Policy?) : GrantVerdict

    /** Not proven: never "probably contained" (GRT-2). [closest] is the ALLOW that got furthest, or the DENY. */
    class Unknown(
        val reason: UnknownReason,
        val shortfall: Shortfall?,
        val detail: String,
        val closest: StatementRef.Policy?,
    ) : GrantVerdict {
        override fun toString(): String = "UNKNOWN($reason${shortfall?.let { ": $it" } ?: ""}) $detail"
    }
}

/** An entry whose change needs `cosec:unprotect` (RFC 0007 §7.4.4). */
@ExperimentalCoSecApi
sealed interface ProtectedEntry {
    data class Policy(val id: PolicyId) : ProtectedEntry

    data class Binding(val id: BindingId) : ProtectedEntry
}

/** The analysis of one change: a verdict for each fragment, and the protected entries it changes or removes. */
@ExperimentalCoSecApi
class GrantAnalysis(val verdicts: List<Pair<GrantFragment, GrantVerdict>>, val unprotect: List<ProtectedEntry>) {
    /** Whether a platform administrator must approve the change (RFC 0007 §7.5). */
    val needsApproval: Boolean get() = verdicts.any { it.second is GrantVerdict.Unknown }
}
