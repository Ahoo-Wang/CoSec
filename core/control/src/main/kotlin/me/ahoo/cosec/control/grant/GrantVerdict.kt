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
import me.ahoo.cosec.model.decision.StatementRef

/** Why a fragment is [GrantVerdict.Unknown]. */
@InternalCoSecApi
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

    /**
     * The fragment is granted to `Anyone` in the default tenant `(0)`, where every unauthenticated caller is: it reaches
     * them as well as the tenant's principals, so it always needs an approval (RC1-35).
     */
    ANONYMOUS,
}

/** How far the closest of the grantor's ALLOWs got, in the order the analysis checks (RFC 0007 §7.4.3). */
@InternalCoSecApi
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
@InternalCoSecApi
sealed interface GrantVerdict {
    /** The grantor holds `cosec:approve` on the document: it is the trust root, and its changes are not analyzed. */
    @InternalCoSecApi
    object TrustRoot : GrantVerdict

    /** Contained in the grantor's statement [by]; `null` when the fragment can apply to no request at all. */
    @InternalCoSecApi
    class Contained(val by: StatementRef.Policy?) : GrantVerdict

    /** Not proven: never "probably contained" (GRT-2). [closest] is the ALLOW that got furthest, or the DENY. */
    @InternalCoSecApi
    class Unknown(
        val reason: UnknownReason,
        val shortfall: Shortfall?,
        val detail: String,
        val closest: StatementRef.Policy?,
    ) : GrantVerdict {
        override fun toString(): String = "UNKNOWN($reason${shortfall?.let { ": $it" } ?: ""}) $detail"
    }
}
