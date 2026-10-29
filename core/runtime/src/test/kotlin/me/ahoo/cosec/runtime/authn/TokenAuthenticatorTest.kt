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

package me.ahoo.cosec.runtime.authn

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.CoSecTokenVerifier
import me.ahoo.cosec.runtime.token.SimpleTokenPrincipal
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipMarkedPrincipal
import me.ahoo.cosec.spi.token.DelegationUnsupportedException
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** RFC 0006 §5.3.2: token verification, revocation (`MEM-4`) and the membership source a token states. */
class TokenAuthenticatorTest {
    private val issuedAt: Instant = Instant.parse("2026-10-01T00:00:00Z")

    /** Verifies the tokens it knows, rejects the rest as invalid. */
    private class Verifier(private val tokens: Map<String, () -> TokenPrincipal>) : TokenVerifier {
        val seen = ArrayList<String>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : TokenPrincipal> verify(accessToken: AccessToken): T {
            seen += accessToken.accessToken
            val principal = tokens[accessToken.accessToken] ?: throw TokenVerificationException("unknown token")
            return principal() as T
        }

        override fun <T : TokenPrincipal> refresh(token: CompositeToken): T = throw UnsupportedOperationException()
    }

    private class Revocations(
        val tokens: Set<String> = emptySet(),
        val subjects: Map<Pair<TenantId, PrincipalId>, Instant> = emptyMap(),
    ) : RevocationReader {
        override suspend fun isRevoked(tokenId: String): Boolean = tokenId in tokens

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            subjects[tenant to principal]
    }

    private fun token(principal: CoSecPrincipal = SimplePrincipal("alice"), issuedAt: Instant? = this.issuedAt) =
        SimpleTokenPrincipal("jti-1", principal, issuedAt)

    private fun inTenant(tenant: String, principal: CoSecPrincipal = SimplePrincipal("alice")) =
        SimpleTenantPrincipal(principal, SimpleTenant(tenant))

    private suspend fun authenticate(
        principal: () -> TokenPrincipal,
        revocations: RevocationReader = RevocationReader.NONE,
        token: String? = "t",
        skew: java.time.Duration = me.ahoo.cosec.spi.token.SubjectRevocation.DEFAULT_SKEW,
    ): AuthnResult = TokenAuthenticator(
        Verifier(mapOf("t" to principal)),
        revocations,
        revocationSkew = skew,
    ).authenticate(Credentials(token, null))

    private suspend fun failure(block: suspend () -> Unit): IllegalStateException = try {
        block()
        throw AssertionError("Nothing was thrown.")
    } catch (e: IllegalStateException) {
        e
    }

    private fun AuthnResult.principal(): AuthenticatedPrincipal = (this as AuthnResult.Authenticated).principal

    private fun AuthnResult.reason(): AuthnResult.RejectReason = (this as AuthnResult.Rejected).reason

    @Test
    fun withoutATokenTheRequestIsAnonymous() = runTest {
        val verifier = Verifier(emptyMap())
        TokenAuthenticator(verifier).authenticate(Credentials(null, "api-key")).assert().isSameAs(AuthnResult.Anonymous)
        verifier.seen.assert().isEmpty()
    }

    @Test
    fun aValidTokenAuthenticates() = runTest {
        val principal = SimplePrincipal(
            "alice",
            policies = setOf("p1"),
            roles = setOf("admin", "editor@team-a"),
            attributes = mapOf("department" to "sales"),
        )
        val result = authenticate({ token(inTenant("acme", principal)) })

        (result as AuthnResult.Authenticated).method.assert().isEqualTo(TokenAuthenticator.METHOD)
        val authenticated = result.principal()
        authenticated.id.assert().isEqualTo(PrincipalId("alice"))
        authenticated.tenant.assert().isEqualTo(TenantId("acme"))
        authenticated.attributes.assert().containsEntry("department", "sales")
        val memberships = authenticated.memberships!!
        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("admin"))
        memberships.roles[SpaceId("team-a")]!!.toList().assert().containsExactly(RoleId("editor"))
    }

    /** Stands in for `cosec-jwt`'s `JwtTokenVerifier`, CoSec's own verifier, which this module cannot depend on. */
    private class OwnVerifier(tokens: Map<String, () -> TokenPrincipal>) : CoSecTokenVerifier {
        private val verifier = Verifier(tokens)

        override fun <T : TokenPrincipal> verify(accessToken: AccessToken): T = verifier.verify(accessToken)

        override fun <T : TokenPrincipal> refresh(token: CompositeToken): T = verifier.refresh(token)
    }

    /**
     * RFC 0009 §4.2, `BRK-8`, RC1-26: break-glass reaches a token CoSec's own verifier verified, by provenance. A token
     * another verifier accepted gets none and is not named `jwt`; nor does a principal some other way, and no other
     * authenticator can name its credential `jwt` to pass for CoSec's token.
     */
    @Test
    fun onlyCoSecsOwnVerifierMayUseBreakGlass() = runTest {
        val own = TokenAuthenticator(OwnVerifier(mapOf("t" to { token() }))).authenticate(Credentials("t", null))
        own.principal().breakGlass.assert().isTrue()
        own.principal().credential!!.method.assert().isEqualTo(Identity.Method.JWT)
        val other = authenticate({ token() }).principal()
        other.breakGlass.assert().isFalse()
        other.credential!!.method.assert().isEqualTo(Identity.Method(TokenAuthenticator.METHOD))
        AuthenticatedPrincipal(PrincipalId("alice"), TenantId("acme"), null).breakGlass.assert().isFalse()
        AuthenticatedPrincipal(
            PrincipalId("alice"),
            TenantId("acme"),
            null,
            credential = Identity.Credential(Identity.Method("ldap"), "s1", null),
        ).breakGlass.assert().isFalse()
        assertThrownBy<IllegalArgumentException> {
            AuthenticatedPrincipal(
                PrincipalId("alice"),
                TenantId("acme"),
                null,
                credential = Identity.Credential(Identity.Method.JWT, "s1", null),
            )
        }
        assertThrownBy<IllegalArgumentException> { TokenAuthenticator(Verifier(emptyMap()), method = "jwt") }
    }

    /** RC1-25: a token cannot pass for an API key by carrying the attribute CoSec sets for one. */
    @Test
    fun aTokenWithAReservedAttributeIsInvalid() = runTest {
        val forged = SimplePrincipal("alice", attributes = mapOf(ApiKey.API_KEY_ID_ATTRIBUTE to "k1"))
        authenticate({ token(forged) }).reason().assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
        authenticate({ token(SimplePrincipal("alice", attributes = mapOf("department" to "sales"))) })
            .principal().attributes.assert().containsEntry("department", "sales")
    }

    @Test
    fun aTokenWithoutTenantIsInTheDefaultTenant() = runTest {
        authenticate({ token() }).principal().tenant.assert().isEqualTo(TenantId.DEFAULT)
    }

    @Test
    fun theProviderMarkerAsksTheProvider() = runTest {
        val marked = MembershipMarkedPrincipal(SimplePrincipal("alice"), MembershipClaims.PROVIDER)
        authenticate(
            { token(marked) }
        ).principal().membershipSource.assert().isSameAs(MembershipSource.Provider.UNCAPPED)
    }

    /** `MEM-1`: the marker together with claims is unusable, never a choice between them. */
    @Test
    fun theMarkerWithClaimsIsUnusable() = runTest {
        val marked = MembershipMarkedPrincipal(
            SimplePrincipal("alice", roles = setOf("admin")),
            MembershipClaims.PROVIDER
        )
        val source = authenticate({ token(marked) }).principal().membershipSource
        (source as MembershipSource.Unusable).reason.assert().contains("also claims roles or policies")
        authenticate({ token(MembershipMarkedPrincipal(SimplePrincipal("alice"), "other")) }).principal()
            .membershipSource.assert().isInstanceOf(MembershipSource.Unusable::class.java)
    }

    @Test
    fun rejectedTokensNameWhy() = runTest {
        authenticate({ throw TokenExpiredException("expired") }).reason()
            .assert().isEqualTo(AuthnResult.RejectReason.TOKEN_EXPIRED)
        authenticate({ throw TokenRevokedException("revoked") }).reason()
            .assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        authenticate({ throw TokenVerificationException("bad signature") }).reason()
            .assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
        authenticate({ throw DelegationUnsupportedException("act") }).reason()
            .assert().isEqualTo(AuthnResult.RejectReason.DELEGATION_UNSUPPORTED)
        authenticate({ token() }, token = "unknown").reason().assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
    }

    @Test
    fun aRevokedTokenIsRejected() = runTest {
        authenticate({ token() }, Revocations(tokens = setOf("jti-1"))).reason()
            .assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        authenticate({ token() }, Revocations(tokens = setOf("jti-2")))
            .assert().isInstanceOf(AuthnResult.Authenticated::class.java)
    }

    /** `MEM-4`: revoking a principal rejects its tokens issued before the instant, in its own tenant only. */
    @Test
    fun aRevokedSubjectsOlderTokensAreRejected() = runTest {
        val alice = TenantId("acme") to PrincipalId("alice")
        val revoked = Revocations(subjects = mapOf(alice to issuedAt.plusSeconds(1)))
        authenticate({ token(inTenant("acme")) }, revoked).reason()
            .assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        authenticate({ token(inTenant("acme"), issuedAt = null) }, revoked).reason()
            .assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        // Issued after the revocation, beyond the skew allowance.
        authenticate({ token(inTenant("acme"), issuedAt = issuedAt.plusSeconds(7)) }, revoked)
            .assert().isInstanceOf(AuthnResult.Authenticated::class.java)
        authenticate({ token(inTenant("other")) }, revoked).assert().isInstanceOf(AuthnResult.Authenticated::class.java)
    }

    /**
     * Review: an issuer whose clock runs ahead stamps a token issued before the revocation with a later `iat`; within
     * the skew allowance it is revoked all the same.
     */
    @Test
    fun aTokenStampedWithinTheSkewOfARevocationIsRejected() = runTest {
        val alice = TenantId("acme") to PrincipalId("alice")
        val revoked = Revocations(subjects = mapOf(alice to issuedAt))
        val ahead = { token(inTenant("acme"), issuedAt = issuedAt.plusSeconds(3)) }
        authenticate(ahead, revoked).reason().assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        authenticate(ahead, revoked, skew = java.time.Duration.ZERO)
            .assert().isInstanceOf(AuthnResult.Authenticated::class.java)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            TokenAuthenticator(Verifier(emptyMap()), revocationSkew = java.time.Duration.ofSeconds(-1))
        }
    }

    /** Anything else the verifier or the reader throws reaches the pipeline, which answers 503. */
    @Test
    fun otherFailuresPropagate() = runTest {
        failure { authenticate({ throw IllegalStateException("bug") }) }.message.assert().isEqualTo("bug")
        val failing = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = throw IllegalStateException("unreadable")

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
        }
        failure { authenticate({ token() }, failing) }.message.assert().isEqualTo("unreadable")
    }
}
