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

/**
 * What a user holds in a tenant, and in an app there: what a [TenantSwitch] answers for the tenant it switches to. The
 * [roles] and [policies] the new token claims, and its [attributes]. [app], when not null, is the app the new token is
 * for, which the issuer's own code names after checking that the user has it in that tenant; null keeps the previous
 * token's. [fromProvider] claims no memberships and marks the token for the `MembershipProvider`
 * (`cosec.membership: provider`), which answers for the tenant on every request.
 */
class TenantMembership private constructor(
    val roles: Set<String>,
    val policies: Set<String>,
    val attributes: Map<String, Any>,
    /** The token asks the `MembershipProvider` for its memberships instead of claiming them. */
    val fromProvider: Boolean,
    val app: String?,
) {
    constructor(
        roles: Set<String> = emptySet(),
        policies: Set<String> = emptySet(),
        attributes: Map<String, Any> = emptyMap(),
        app: String? = null,
    ) : this(roles, policies, attributes, fromProvider = false, app = app)

    companion object {
        /** A token without roles or policies that asks the `MembershipProvider` (RFC 0004 §4.5.2), for [app] if named. */
        fun fromProvider(attributes: Map<String, Any> = emptyMap(), app: String? = null): TenantMembership =
            TenantMembership(emptySet(), emptySet(), attributes, fromProvider = true, app = app)
    }
}
