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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.spi.token.VerifiedToken

/**
 * What a new token says about whom it is issued for: the one input of a [TokenConverter], an immutable value.
 *
 * [roles] holds what the principal may hold in the token's [app], as `role` or `role@space` (RFC 0012 §3.5).
 */
data class TokenSubject(
    /** The principal's id: the token's subject. Not empty, and not beginning with `(`, which is CoSec's (`IDN-12`). */
    val id: String,
    /**
     * The app the token is for (`aud`), null for none: a host with an app refuses a token declaring another or none
     * (`SCP-11`). No default: whoever issues a token says which app it is for.
     */
    val app: String?,
    /** The principal's tenant; null puts it in the default tenant. */
    val tenant: String? = null,
    val roles: Set<String> = emptySet(),
    val policies: Set<String> = emptySet(),
    val attributes: Map<String, Any> = emptyMap(),
    /**
     * The `cosec.membership` claim: [MembershipClaims.PROVIDER][me.ahoo.cosec.spi.membership.MembershipClaims.PROVIDER]
     * to have the `MembershipProvider` answer for the token's memberships, null when the token carries its own.
     */
    val membershipMarker: String? = null,
) {
    companion object {
        /** What the token [token] said, so that refreshing it says the same, its app and tenant included. */
        fun of(token: VerifiedToken): TokenSubject = TokenSubject(
            id = token.principal,
            app = token.app,
            tenant = token.tenant,
            roles = token.roles,
            policies = token.policies,
            attributes = token.attributes,
            membershipMarker = token.membershipMarker,
        )
    }
}
