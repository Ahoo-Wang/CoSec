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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.context.ForwardedIdentity
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The documented extension point for switching tenant and app (`wiki/integration/authentication.md`). */
class SwitchTenantAuthenticationTest {
    /** An application's own credentials: what CoSec asks for, and a device id its switch reads. */
    private class Credentials(
        override val targetTenantId: String,
        override val context: SecurityContext,
        override val targetAppId: String? = null,
        val deviceId: String = "phone",
    ) : SwitchTenantCredentials

    private val families = TokenFamilies(InMemoryTokenFamilyStore())

    /** Alice's session in acme, live: the one every switch here issues from. */
    private val signedIn = runBlocking { families.start(TenantId("acme"), PrincipalId("alice")) }

    /** Lets a principal into the tenants it is a member of, with what it holds there, and nowhere else. */
    @Suppress("FunctionName")
    private fun Switching(members: Map<String, TenantMembership>) =
        SwitchTenantAuthentication(
            families,
            TokenRevocation(RevocationReader.NONE),
            Credentials::class.java
        ) { credentials ->
            requireNotNull(members[credentials.targetTenantId]) {
                "[${credentials.context.principal.id}] may not enter [${credentials.targetTenantId}]."
            }
        }

    /** An administrator of the tenant it switches from, with an attribute of that tenant, as the pipeline let it in. */
    private fun context(app: String? = null) = PermittedRequest.withToken(
        signedIn.session,
        attributes = mapOf("department" to "acme-finance"),
        app = app,
    )

    private val alice = context()

    /** A login finds the switch by the application's own credentials class (`TokenCompositeAuthentication`). */
    @Test
    fun `it supports the application's credentials class`() {
        Switching(emptyMap()).credentialsType.assert().isEqualTo(Credentials::class.java)
    }

    @Test
    fun `the switch reads the application's own credentials without a cast`() = runTest {
        val byDevice = SwitchTenantAuthentication(
            families,
            TokenRevocation(RevocationReader.NONE),
            Credentials::class.java,
        ) { credentials ->
            TenantMembership(attributes = mapOf("device" to credentials.deviceId))
        }
        byDevice.authenticate(Credentials("globex", alice, deviceId = "tablet")).attributes
            .assert().isEqualTo(mapOf("device" to "tablet"))
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

    /** `SCP-11`, `SCP-12`: without an app in the answer, the new token keeps the previous one's, or none. */
    @Test
    fun `the new token keeps the app of the previous one`() = runTest {
        val member = Switching(mapOf("globex" to TenantMembership()))
        member.authenticate(Credentials("globex", context(app = "order"))).app.assert().isEqualTo("order")
        member.authenticate(Credentials("globex", context())).app.assert().isNull()
    }

    /** `SCP-12`: the app is the issuer's decision, so the switch's answer names it, from a token that had none too. */
    @Test
    fun `a switch that answers an app issues the token for it`() = runTest {
        val clerk = Switching(mapOf("globex" to TenantMembership(roles = setOf("clerk"), app = "order")))
        clerk.authenticate(Credentials("globex", context(), targetAppId = "order")).app.assert().isEqualTo("order")
        clerk.authenticate(Credentials("globex", context(app = "billing"))).app.assert().isEqualTo("order")
        val provided = Switching(mapOf("globex" to TenantMembership.fromProvider(app = "order")))
        provided.authenticate(Credentials("globex", context())).app.assert().isEqualTo("order")
    }

    /** `SCP-12`: an app the request names reaches the token only through the answer, never as named. */
    @Test
    fun `an app the credentials name and the answer does not return is never issued`() = runTest {
        val member = Switching(mapOf("globex" to TenantMembership()))
        member.authenticate(Credentials("globex", context(), targetAppId = "order")).app.assert().isNull()
        member.authenticate(Credentials("globex", context(app = "billing"), targetAppId = "order"))
            .app.assert().isEqualTo("billing")
    }

    /** `SCP-10`: the answered app becomes the token's `aud`, so it must be one a token can carry. */
    @Test
    fun `an answered app that cannot be an app id refuses the switch`() = runTest {
        listOf("", "*", "a:b").forEach { app ->
            assertThrows<IllegalArgumentException> {
                Switching(mapOf("globex" to TenantMembership(app = app))).authenticate(Credentials("globex", alice))
            }
        }
    }

    /**
     * RFC 0009, `IDN-17`: the user did not sign in again, so the new token is in a session of its own that keeps the
     * login time of the one switched from, and ends when it would. That session is the request's, as CoSec's token
     * proved it: nothing the credentials say.
     */
    @Test
    fun `the new session keeps the login time of the session switched from`() = runTest {
        val subject = Switching(mapOf("globex" to TenantMembership())).authenticate(Credentials("globex", alice))
        val session = checkNotNull(subject.session)
        session.authTime.assert().isEqualTo(signedIn.session.authTime)
        session.id.assert().isNotEqualTo(signedIn.session.id)
        session.generation.assert().isEqualTo(1L)
        subject.notAfter.assert().isEqualTo(signedIn.notAfter)
    }

    /** Only a request the pipeline permitted with CoSec's own token has a session to switch from. */
    @Test
    fun `a request without a session of CoSec's token cannot switch`() = runTest {
        val member = Switching(mapOf("globex" to TenantMembership()))
        assertThrows<TokenVerificationException> {
            member.authenticate(Credentials("globex", PermittedRequest.withToken(session = null)))
        }
        assertThrows<TokenVerificationException> { member.authenticate(Credentials("globex", unmade())) }
    }

    @Test
    fun `an anonymous request cannot switch tenant`() = runTest {
        assertThrows<IllegalStateException> {
            Switching(mapOf("globex" to TenantMembership()))
                .authenticate(Credentials("globex", unmade(authenticated = false)))
        }
    }

    @Test
    fun `a switch the implementation refuses is an error`() = runTest {
        assertThrows<IllegalArgumentException> {
            Switching(mapOf("acme" to TenantMembership())).authenticate(Credentials("globex", alice))
        }
    }

    /** A context the pipeline did not make, as an application could build one: it proves no session. */
    private fun unmade(authenticated: Boolean = true) = object : SecurityContext {
        override val tenantId: String = "acme"
        override val principal: CoSecPrincipal = object : CoSecPrincipal {
            override val id: String = "alice"
            override val authenticated: Boolean = authenticated
            override val attributes: Map<String, Any> = emptyMap()
        }
        override val scope: SecurityScope = object : SecurityScope {
            override val appId: String = ""
            override val spaceId: String = ""
            override val roles: List<String> = listOf("admin")
        }
        override val forwardedIdentity: ForwardedIdentity? = null
    }
}
