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
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.CoSecTokenVerifier
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.DelegationUnsupportedException
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** RFC 0006 §5.3.2: token verification, revocation (`MEM-4`) and the membership source a token states. */
class TokenAuthenticatorTest {
    private val issuedAt: Instant = Instant.parse("2026-10-01T00:00:00Z")

    /** Verifies the tokens it knows, rejects the rest as invalid. */
    private class Verifier(private val tokens: Map<String, () -> VerifiedToken>) : TokenVerifier {
        val seen = ArrayList<String>()

        override fun verify(accessToken: String): VerifiedToken {
            seen += accessToken
            val token = tokens[accessToken] ?: throw TokenVerificationException("unknown token")
            return token()
        }

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private class Revocations(
        val tokens: Set<String> = emptySet(),
        val subjects: Map<Pair<TenantId, PrincipalId>, Instant> = emptyMap(),
    ) : RevocationReader {
        override suspend fun isRevoked(tokenId: String): Boolean = tokenId in tokens

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            subjects[tenant to principal]
    }

    private val alice = VerifiedToken("jti-1", "alice", null, null)

    private fun VerifiedToken.with(tenant: String? = this.tenant, issuedAt: Instant? = this.issuedAt) =
        VerifiedToken(id, principal, tenant, app, roles, policies, attributes, issuedAt, expiresAt, membershipMarker)

    private fun token(claims: VerifiedToken = alice, issuedAt: Instant? = this.issuedAt) = claims.with(
        issuedAt = issuedAt
    )

    private fun inTenant(tenant: String, claims: VerifiedToken = alice) = claims.with(tenant = tenant)

    private suspend fun authenticate(
        principal: () -> VerifiedToken,
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
        val principal =
            VerifiedToken(
                "jti-1",
                "alice",
                null,
                null,
                roles = setOf("admin", "editor@team-a"),
                policies = setOf("p1"),
                attributes = mapOf("department" to "sales")
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

    /** RFC 0012 §3.5: the token's `aud` is the app of its principal; a token declaring none declares none. */
    @Test
    fun theAppOfATokenIsTheAppOfItsPrincipal() = runTest {
        authenticate({
            token(VerifiedToken("jti-1", "alice", null, "order"))
        }).principal().app.assert().isEqualTo(AppId("order"))
        authenticate({ token() }).principal().app.assert().isNull()
    }

    /** `IDN-11`: only a token with no tenant claim is in the default tenant, whichever verifier read the claim. */
    @Test
    fun aTokenClaimingAnEmptyTenantIsInvalid() = runTest {
        authenticate({ token(inTenant("")) }).reason().assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
    }

    /**
     * RC1-52, `IDN-10`: a JSON token can claim an id with an unpaired surrogate (`"\uD800"`), which a Redis key writes
     * as `?`. Such a token is invalid (401 `token-invalid`), and no revocation is read for it: its keys would be another's.
     */
    @Test
    fun aTokenClaimingAnIdWithAnUnpairedSurrogateIsInvalid() = runTest {
        val asked = ArrayList<String>()
        val revocations = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = false.also { asked += tokenId }

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
                null.also { asked += principal.value }
        }
        val tokens = listOf<() -> VerifiedToken>(
            { token(VerifiedToken("jti", "alice\uD800", null, null)) },
            { token(inTenant("beta\uD800")) },
            { token(inTenant("\uDC00beta")) },
            { VerifiedToken("jti\uD800", "alice", null, null, issuedAt = issuedAt) },
            { token(VerifiedToken("jti-1", "alice", null, null, roles = setOf("admin\uDC00"))) },
            { token(VerifiedToken("jti-1", "alice", null, null, roles = setOf("admin@team\uD800"))) },
            { token(VerifiedToken("jti-1", "alice", null, null, policies = setOf("p\uD800"))) },
        )
        for (token in tokens) {
            authenticate(token, revocations).reason().assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
        }
        asked.assert().isEmpty()

        // A surrogate pair is one character like any other.
        val paired =
            authenticate({
                token(inTenant("beta\uD83D\uDE00", VerifiedToken("jti-1", "alice\uD83D\uDE00", null, null)))
            }, revocations)
        paired.principal().tenant.assert().isEqualTo(TenantId("beta\uD83D\uDE00"))
        asked.assert().containsExactly("jti-1", "alice\uD83D\uDE00")
    }

    /** Stands in for `cosec-jwt`'s `JwtTokenVerifier`, CoSec's own verifier, which this module cannot depend on. */
    private class OwnVerifier(tokens: Map<String, () -> VerifiedToken>) : CoSecTokenVerifier {
        private val verifier = Verifier(tokens)

        override fun verify(accessToken: String): VerifiedToken = verifier.verify(accessToken)

        override fun refresh(token: CompositeToken): VerifiedToken = verifier.refresh(token)
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
        own.principal().apiKey.assert().isFalse()
        val other = authenticate({ token() }).principal()
        other.breakGlass.assert().isFalse()
        other.credential!!.method.assert().isEqualTo(Identity.Method(TokenAuthenticator.METHOD))
        AuthenticatedPrincipal(PrincipalId("alice"), TenantId("acme"), null).breakGlass.assert().isFalse()
        // Review M2: a credential named api-key is an API key, whoever made it (a gateway's forwarded identity): fail
        // closed. Another name is not.
        AuthenticatedPrincipal(
            PrincipalId("svc"),
            TenantId("acme"),
            null,
            credential = Identity.Credential(Identity.Method.API_KEY, "k1", null),
        ).apiKey.assert().isTrue()
        AuthenticatedPrincipal(
            PrincipalId("alice"),
            TenantId("acme"),
            null,
            credential = Identity.Credential(Identity.Method("ldap"), "s1", null),
        ).apiKey.assert().isFalse()
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
        val forged =
            VerifiedToken("jti-1", "alice", null, null, attributes = mapOf(ApiKey.API_KEY_ID_ATTRIBUTE to "k1"))
        authenticate({ token(forged) }).reason().assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
        authenticate(
            { token(VerifiedToken("jti-1", "alice", null, null, attributes = mapOf("department" to "sales"))) }
        )
            .principal().attributes.assert().containsEntry("department", "sales")
    }

    @Test
    fun aTokenWithoutTenantIsInTheDefaultTenant() = runTest {
        authenticate({ token() }).principal().tenant.assert().isEqualTo(TenantId.DEFAULT)
    }

    @Test
    fun theProviderMarkerAsksTheProvider() = runTest {
        val marked = VerifiedToken("jti-1", "alice", null, null, membershipMarker = MembershipClaims.PROVIDER)
        authenticate(
            { token(marked) }
        ).principal().membershipSource.assert().isSameAs(MembershipSource.Provider.UNCAPPED)
    }

    /** `MEM-1`: the marker together with claims is unusable, never a choice between them. */
    @Test
    fun theMarkerWithClaimsIsUnusable() = runTest {
        val marked =
            VerifiedToken(
                "jti-1",
                "alice",
                null,
                null,
                roles = setOf("admin"),
                membershipMarker = MembershipClaims.PROVIDER
            )
        val source = authenticate({ token(marked) }).principal().membershipSource
        (source as MembershipSource.Unusable).reason.assert().contains("also claims roles or policies")
        authenticate({ token(VerifiedToken("jti-1", "alice", null, null, membershipMarker = "other")) }).principal()
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
