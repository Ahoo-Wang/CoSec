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

package me.ahoo.cosec.membership

import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.principal.CoSecPrincipal

/**
 * A principal whose token carried the [MembershipClaims.MARKER_KEY] claim.
 */
interface MembershipMarkerCapable {
    /**
     * The claim's value: [MembershipClaims.PROVIDER], or anything else the token said (a value that is not a string is
     * kept as its JSON text), which [MembershipResolver] refuses.
     */
    val membershipMarker: String
}

/**
 * [delegate] as its token stated it, plus the [MembershipClaims.MARKER_KEY] claim. Token parsers wrap a principal only
 * when the claim is present, so principals of tokens without it are unchanged.
 */
data class MembershipMarkedPrincipal(
    override val delegate: CoSecPrincipal,
    override val membershipMarker: String,
) : CoSecPrincipal by delegate,
    MembershipMarkerCapable,
    Delegated<CoSecPrincipal>
