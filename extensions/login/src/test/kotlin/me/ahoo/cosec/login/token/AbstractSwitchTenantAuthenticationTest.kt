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

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.context.ForwardedIdentity
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The documented extension point for switching tenants (`wiki/integration/authentication.md`). */
class AbstractSwitchTenantAuthenticationTest {
    /** Lets a principal into the tenants it is a member of, with what it holds there, and nowhere else. */
    private class Switching(private val members: Map<String, TenantMembership>) : AbstractSwitchTenantAuthentication() {
        override suspend fun switchTenant(
            targetTenantId: String,
            previous: SecurityContext
        ): TenantMembership = requireNotNull(members[targetTenantId]) {
            "[${previous.principal.id}] may not enter [$targetTenantId]."
        }
    }

    private class Credentials(
        override val targetTenantId: String,
        override val context: SecurityContext,
    ) : SwitchTenantCredentials

    /** An administrator of the tenant it switches from, with a policy and an attribute of that tenant. */
    private fun context(app: String = "", authenticated: Boolean = true) = object : SecurityContext {
        override val tenantId: String = "acme"
        override val principal: CoSecPrincipal = object : CoSecPrincipal {
            override val id: String = "alice"
            override val authenticated: Boolean = authenticated
            override val attributes: Map<String, Any> = mapOf("department" to "acme-finance")
        }
        override val scope: SecurityScope = object : SecurityScope {
            override val appId: String = app
            override val spaceId: String = ""
            override val roles: List<String> = listOf("admin")
        }
        override val forwardedIdentity: ForwardedIdentity? = null
    }

    private val alice = context()

    @Test
    fun `it supports switch-tenant credentials`() {
        Switching(emptyMap()).supportCredentials.assert().isEqualTo(SwitchTenantCredentials::class.java)
    }

    /** RC1-23: the new token holds the target tenant's memberships only, never the previous tenant's. */
    @Test
    fun `the principal is switched into the target tenant with that tenant's memberships only`() = runTest {
        val viewer = TenantMembership(roles = setOf("viewer"), attributes = mapOf("department" to "globex-sales"))
        val subject = Switching(mapOf("globex" to viewer)).authenticate(Credentials("globex", alice))
        subject.tenant.assert().isEqualTo("globex")
        subject.id.assert().isEqualTo("alice")
        subject.roles.assert().containsExactly("viewer")
        subject.policies.assert().isEmpty()
        subject.attributes.assert().isEqualTo(mapOf("department" to "globex-sales"))
        subject.membershipMarker.assert().isNull()
    }

    @Test
    fun `a switch to the provider's memberships claims none`() = runTest {
        val subject = Switching(mapOf("globex" to TenantMembership.fromProvider()))
            .authenticate(Credentials("globex", alice))
        subject.tenant.assert().isEqualTo("globex")
        subject.roles.assert().isEmpty()
        subject.policies.assert().isEmpty()
        subject.attributes.assert().isEmpty()
        subject.membershipMarker.assert().isEqualTo(MembershipClaims.PROVIDER)
    }

    /** `SCP-11`: a user cannot change the app of a token by switching tenant, and a token for no app stays so. */
    @Test
    fun `the new token keeps the app of the previous one`() = runTest {
        val member = Switching(mapOf("globex" to TenantMembership()))
        member.authenticate(Credentials("globex", context(app = "order"))).app.assert().isEqualTo("order")
        member.authenticate(Credentials("globex", context())).app.assert().isNull()
    }

    @Test
    fun `an anonymous request cannot switch tenant`() = runTest {
        assertThrows<IllegalStateException> {
            Switching(mapOf("globex" to TenantMembership()))
                .authenticate(Credentials("globex", context(authenticated = false)))
        }
    }

    @Test
    fun `a switch the implementation refuses is an error`() = runTest {
        assertThrows<IllegalArgumentException> {
            Switching(mapOf("acme" to TenantMembership())).authenticate(Credentials("globex", alice))
        }
    }
}
