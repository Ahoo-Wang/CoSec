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

import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.decision.StatementRef

/** Who a fragment is granted to (RFC 0007 §7.4.1). */
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
 */
class GrantFragment(
    val tenant: TenantId,
    val policy: PolicyId,
    /** The statement's index in the policy. */
    val statement: Int,
    val action: ActionPattern,
    val resource: ResourcePattern,
    val condition: Condition?,
    val grantee: Grantee,
    val scope: ScopeSet,
) {
    override fun toString(): String = "${policy.value}[$statement] $action on $resource to $grantee in $scope"
}

/** Why a fragment is [GrantVerdict.Unknown]. */
enum class UnknownReason {
    /** The grantor is not in the tenant of the document. */
    CROSS_TENANT,

    /** No single ALLOW of the grantor is proven to contain the fragment. */
    NOT_COVERED,

    /** A DENY that binds the grantor (other than through `Anyone`) may overlap the fragment. */
    DENY_OVERLAP,
}

/** How far the closest of the grantor's ALLOWs got, in the order the analysis checks (RFC 0007 §7.4.3). */
enum class Shortfall {
    /** The grantor holds no ALLOW at all. */
    NO_ALLOW,

    /** The ALLOW does not apply to the grantor everywhere the fragment is granted. */
    SCOPE,
    ACTION,
    RESOURCE,

    /** The grantor's condition, specialized for the grantor, is not implied by the fragment's. */
    CONDITION,
}

/** What the analysis concluded about one fragment. Only [Unknown] needs approval (RFC 0007 §7.5). */
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
sealed interface ProtectedEntry {
    data class Policy(val id: PolicyId) : ProtectedEntry

    data class Binding(val id: BindingId) : ProtectedEntry
}

/** The analysis of one change: a verdict for each fragment, and the protected entries it changes or removes. */
class GrantAnalysis(val verdicts: List<Pair<GrantFragment, GrantVerdict>>, val unprotect: List<ProtectedEntry>) {
    /** Whether a platform administrator must approve the change (RFC 0007 §7.5). */
    val needsApproval: Boolean get() = verdicts.any { it.second is GrantVerdict.Unknown }
}
