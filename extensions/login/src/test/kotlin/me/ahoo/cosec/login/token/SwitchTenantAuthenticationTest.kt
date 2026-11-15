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
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** The documented extension point for switching tenant and app (`wiki/integration/authentication.md`). */
class SwitchTenantAuthenticationTest {
    /** An application's own credentials: what CoSec asks for, and a device id its switch reads. */
    private class Credentials(
        override val targetTenantId: String,
        override val context: SecurityContext,
        override val accessToken: String = "alice-in-acme",
        override val targetAppId: String? = null,
        val deviceId: String = "phone",
    ) : SwitchTenantCredentials

    /** Lets a principal into the tenants it is a member of, with what it holds there, and nowhere else. */
    @Suppress("FunctionName")
    private fun Switching(
        members: Map<String, TenantMembership>,
        verifier: TokenVerifier = Tokens,
    ) = SwitchTenantAuthentication(verifier, Credentials::class.java) { credentials ->
        requireNotNull(members[credentials.targetTenantId]) {
            "[${credentials.context.principal.id}] may not enter [${credentials.targetTenantId}]."
        }
    }

    /** The tokens the switch is presented with, by their text. */
    private object Tokens : TokenVerifier {
        val signedIn: Instant = Instant.parse("2026-11-01T08:00:00Z")

        override fun verify(accessToken: String): VerifiedToken = when (accessToken) {
            "alice-in-acme" -> VerifiedToken("t1", "alice", "acme", null, session = TokenSession("s1", signedIn, 3))
            "bob-in-acme" -> VerifiedToken("t2", "bob", "acme", null, session = TokenSession("s2", signedIn, 1))
            "alice-without-session" -> VerifiedToken("t3", "alice", "acme", null)
            else -> throw TokenVerificationException("unknown token")
        }

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

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

    /** A login finds the switch by the application's own credentials class (`TokenCompositeAuthentication`). */
    @Test
    fun `it supports the application's credentials class`() {
        Switching(emptyMap()).credentialsType.assert().isEqualTo(Credentials::class.java)
    }

    @Test
    fun `the switch reads the application's own credentials without a cast`() = runTest {
        val byDevice = SwitchTenantAuthentication(Tokens, Credentials::class.java) { credentials ->
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
     * RFC 0009: the user did not sign in again, so the new session keeps the login time of the one switched from, and
     * ends when it would. The token presented is verified, and must be the request's principal's.
     */
    @Test
    fun `the new session keeps the login time of the token switched from`() = runTest {
        val member = Switching(mapOf("globex" to TenantMembership()))
        val subject = member.authenticate(Credentials("globex", alice))
        subject.authTime.assert().isEqualTo(Tokens.signedIn)
        subject.session.assert().isNull()
        assertThrows<TokenVerificationException> { member.authenticate(Credentials("globex", alice, "bob-in-acme")) }
        assertThrows<TokenVerificationException> {
            member.authenticate(Credentials("globex", alice, "alice-without-session"))
        }
        assertThrows<TokenVerificationException> { member.authenticate(Credentials("globex", alice, "forged")) }
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
