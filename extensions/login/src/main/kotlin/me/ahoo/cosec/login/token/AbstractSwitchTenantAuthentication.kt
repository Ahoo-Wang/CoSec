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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.Credentials
import me.ahoo.cosec.spi.membership.MembershipClaims
import reactor.core.publisher.Mono

/**
 * Switches a signed-in principal to another tenant (`wiki/integration/authentication.md`), to issue a token for it.
 *
 * Roles, policies and attributes are a tenant's: an administrator of one tenant is not one of another. So the subject
 * this answers has the [previous][SwitchTenantCredentials.context] principal's id, the target tenant, and only what
 * [switchTenant] answers for that tenant; nothing else of the previous token reaches the new one (RC1-23) but its app,
 * which the token was issued for and a user cannot change by switching tenant (`SCP-11`).
 *
 * @author ahoo wang
 */
abstract class AbstractSwitchTenantAuthentication : Authentication<SwitchTenantCredentials> {
    override val supportCredentials: Class<SwitchTenantCredentials>
        get() = SwitchTenantCredentials::class.java

    final override fun authenticate(credentials: SwitchTenantCredentials): Mono<TokenSubject> {
        val target = credentials.targetTenantId
        val previous = credentials.context
        return Mono.defer {
            check(previous.principal.authenticated) { "An anonymous request has no tenant to switch from." }
            switchTenant(target, previous)
        }.map { inTarget(previous, target, it) }
    }

    /**
     * What [previous]'s user holds in [targetTenantId], looked up for that tenant, never copied from
     * [previous]. An error, or empty, refuses the switch: check here that the user belongs to the tenant.
     *
     * @param targetTenantId target tenant id
     * @param previous the permitted request of the token the user switches from
     * @return the memberships of the new token
     */
    protected abstract fun switchTenant(
        targetTenantId: String,
        previous: SecurityContext
    ): Mono<TenantMembership>

    private fun inTarget(previous: SecurityContext, target: String, membership: TenantMembership): TokenSubject =
        TokenSubject(
            id = previous.principal.id,
            app = previous.scope.appId.ifEmpty { null },
            tenant = target,
            roles = membership.roles,
            policies = membership.policies,
            attributes = membership.attributes,
            membershipMarker = if (membership.fromProvider) MembershipClaims.PROVIDER else null,
        )
}

/**
 * What a user holds in the tenant it switches to ([AbstractSwitchTenantAuthentication.switchTenant]): the [roles] and
 * [policies] the new token claims, and its [attributes]. [fromProvider] claims none and marks the token for the
 * `MembershipProvider` (`cosec.membership: provider`), which answers for the target tenant on every request.
 */
class TenantMembership private constructor(
    val roles: Set<String>,
    val policies: Set<String>,
    val attributes: Map<String, Any>,
    /** The token asks the `MembershipProvider` for its memberships instead of claiming them. */
    val fromProvider: Boolean,
) {
    constructor(
        roles: Set<String> = emptySet(),
        policies: Set<String> = emptySet(),
        attributes: Map<String, Any> = emptyMap(),
    ) : this(roles, policies, attributes, fromProvider = false)

    companion object {
        /** A token without roles or policies that asks the `MembershipProvider` (RFC 0004 §4.5.2). */
        fun fromProvider(attributes: Map<String, Any> = emptyMap()): TenantMembership =
            TenantMembership(emptySet(), emptySet(), attributes, fromProvider = true)
    }
}

/**
 * Switch Tenant Credentials .
 *
 * @author ahoo wang
 */
interface SwitchTenantCredentials : Credentials {
    val targetTenantId: String

    /** The context of the permitted request asking to switch: the token the user switches from. */
    val context: SecurityContext
}
