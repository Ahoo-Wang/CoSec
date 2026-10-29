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

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/** The documented extension point for switching tenants (`wiki/integration/authentication.md`). */
class AbstractSwitchTenantAuthenticationTest {
    /** Lets a principal into the tenants it is a member of, with what it holds there, and nowhere else. */
    private class Switching(private val members: Map<String, TenantMembership>) : AbstractSwitchTenantAuthentication() {
        override fun switchTenant(
            targetTenantId: String,
            previousPrincipal: CoSecPrincipal
        ): Mono<TenantMembership> {
            val membership = members[targetTenantId] ?: return Mono.error(
                IllegalArgumentException("[${previousPrincipal.id}] may not enter [$targetTenantId].")
            )
            return Mono.just(membership)
        }
    }

    private class Credentials(
        override val targetTenantId: String,
        override val principal: CoSecPrincipal,
    ) : SwitchTenantCredentials

    /** An administrator of the tenant it switches from, with a policy and an attribute of that tenant. */
    private val alice = SimplePrincipal(
        "alice",
        policies = setOf("acme-export"),
        roles = setOf("admin"),
        attributes = mapOf("department" to "acme-finance"),
    )

    @Test
    fun `it supports switch-tenant credentials`() {
        Switching(emptyMap()).supportCredentials.assert().isEqualTo(SwitchTenantCredentials::class.java)
    }

    /** RC1-23: the new token holds the target tenant's memberships only, never the previous tenant's. */
    @Test
    fun `the principal is switched into the target tenant with that tenant's memberships only`() {
        val viewer = TenantMembership(roles = setOf("viewer"), attributes = mapOf("department" to "globex-sales"))
        Switching(mapOf("globex" to viewer)).authenticate(Credentials("globex", alice))
            .test()
            .consumeNextWith {
                it.tenant.tenantId.assert().isEqualTo("globex")
                it.id.assert().isEqualTo("alice")
                it.roles.assert().containsExactly("viewer")
                it.policies.assert().isEmpty()
                it.attributes.assert().isEqualTo(mapOf("department" to "globex-sales"))
                MembershipClaims.markerOf(it).assert().isNull()
            }
            .verifyComplete()
    }

    @Test
    fun `a switch to the provider's memberships claims none`() {
        Switching(mapOf("globex" to TenantMembership.fromProvider())).authenticate(Credentials("globex", alice))
            .test()
            .consumeNextWith {
                it.tenant.tenantId.assert().isEqualTo("globex")
                it.roles.assert().isEmpty()
                it.policies.assert().isEmpty()
                it.attributes.assert().isEmpty()
                MembershipClaims.markerOf(it).assert().isEqualTo(MembershipClaims.PROVIDER)
            }
            .verifyComplete()
    }

    @Test
    fun `a switch the implementation refuses is an error`() {
        Switching(mapOf("acme" to TenantMembership())).authenticate(Credentials("globex", alice))
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }
}
