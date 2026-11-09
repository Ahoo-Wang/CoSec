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

package me.ahoo.cosec.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.RefreshTokenMismatchException
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.DelegationUnsupportedException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

class JwtTokenVerifierTest {
    var jwtTokenConverter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    @Test
    fun verify() {
        val token: CompositeToken = jwtTokenConverter.toToken(TokenSubject("alice", null))
        jwtTokenVerifier.verify(token.accessToken).principal.assert().isEqualTo("alice")
    }

    @Test
    fun verifyWhenExpiresAtMissing() {
        val token = JWT.create().withSubject("subject").sign(JwtFixture.ALGORITHM)
        val thrown = assertThrows(TokenVerificationException::class.java) {
            jwtTokenVerifier.verify(token)
        }
        // Missing exp is an invalid token, not an expired one — pin the mapping.
        assertFalse(thrown is TokenExpiredException)
    }

    private fun signed(builder: JWTCreator.Builder.() -> Unit): String = JWT.create()
        .withJWTId("t1")
        .withSubject("alice")
        .withExpiresAt(Instant.now().plusSeconds(3600))
        .apply(builder)
        .sign(JwtFixture.ALGORITHM)

    /** `IDN-4`: a delegation token never becomes a principal, whatever else about it is valid. */
    @Test
    fun aDelegationTokenIsRefused() {
        val delegated = signed { withClaim("act", mapOf("sub" to "agent")) }
        assertThrownBy<DelegationUnsupportedException> {
            jwtTokenVerifier.verify(delegated)
        }
        // Any value counts: an actor CoSec cannot read is still an actor.
        assertThrownBy<DelegationUnsupportedException> {
            jwtTokenVerifier.verify(signed { withNullClaim("act") })
        }
        assertThrownBy<DelegationUnsupportedException> {
            jwtTokenVerifier.verify(signed { withClaim("act", "agent") })
        }
    }

    @Test
    fun refreshingADelegationTokenIsRefused() {
        val access = signed { withClaim("act", mapOf("sub" to "agent")) }
        val refresh = JWT.create().withSubject("t1").withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(JwtFixture.ALGORITHM)
        assertThrownBy<DelegationUnsupportedException> {
            jwtTokenVerifier.refresh(SimpleCompositeToken(access, refresh))
        }
    }

    /** A malformed access token is an invalid token, not an unmodelled error. */
    @Test
    fun refreshingAMalformedAccessTokenIsInvalid() {
        val refresh = JWT.create().withSubject("t1").withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(JwtFixture.ALGORITHM)
        val thrown = assertThrows(TokenVerificationException::class.java) {
            jwtTokenVerifier.refresh(SimpleCompositeToken("not-a-jwt", refresh))
        }
        assertFalse(thrown is TokenExpiredException)
    }

    /**
     * RC1-22: a `tenantId` claim that is not a string is an invalid token, never the default tenant with the token's
     * roles. An absent claim is the default tenant.
     */
    @Test
    fun aTenantIdThatIsNotAStringIsInvalid() {
        for (token in listOf(
            signed { withClaim("tenantId", 42) },
            signed { withNullClaim("tenantId") },
            signed { withClaim("tenantId", mapOf("id" to "acme")) },
            signed { withClaim("tenantId", true) },
        )) {
            val thrown = assertThrows(TokenVerificationException::class.java) {
                jwtTokenVerifier.verify(token)
            }
            assertFalse(thrown is TokenExpiredException)
        }
        jwtTokenVerifier.verify(signed { withClaim("tenantId", "acme") })
            .tenant.assert().isEqualTo("acme")
        jwtTokenVerifier.verify(signed {}).tenant.assert().isNull()
    }

    /** `IDN-11`: an empty `tenantId` claim is invalid on both paths that read one, never the default tenant. */
    @Test
    fun anEmptyTenantIdIsInvalid() {
        val access = signed { withClaim("tenantId", "") }
        val refresh = JWT.create().withSubject("t1").withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(JwtFixture.ALGORITHM)
        for (read in listOf<() -> VerifiedToken>(
            { jwtTokenVerifier.verify(access) },
            { jwtTokenVerifier.refresh(SimpleCompositeToken(access, refresh)) },
        )) {
            val thrown = assertThrows(TokenVerificationException::class.java) { read() }
            assertFalse(thrown is TokenExpiredException)
        }
    }

    /**
     * A `roles` or `policies` claim that is not an array of strings, or an `attributes` claim that is not an object, is
     * an invalid token (401, `PIP-6`), never a principal with fewer roles than it was given, and never a 500. Absent
     * claims are empty, as before.
     */
    @Test
    fun malformedRoleAndPolicyClaimsAreInvalid() {
        for (claim in listOf("roles", "policies")) {
            for (token in listOf(
                signed { withClaim(claim, "admin") },
                signed { withNullClaim(claim) },
                signed { withClaim(claim, mapOf("admin" to true)) },
                signed { withClaim(claim, listOf(1, 2)) },
                signed { withClaim(claim, 42) },
            )) {
                val thrown = assertThrows(TokenVerificationException::class.java) {
                    jwtTokenVerifier.verify(token)
                }
                assertFalse(thrown is TokenExpiredException)
            }
        }
        for (token in listOf(
            signed { withClaim("attributes", "region") },
            signed { withClaim("attributes", listOf("a")) }
        )) {
            assertThrows(TokenVerificationException::class.java) {
                jwtTokenVerifier.verify(token)
            }
        }
        val principal = jwtTokenVerifier.verify(
            signed { withClaim("roles", listOf("admin")).withClaim("policies", listOf("p1")) }
        )
        principal.roles.assert().containsExactly("admin")
        principal.policies.assert().containsExactly("p1")
        jwtTokenVerifier.verify(signed {}).roles.assert().isEmpty()
    }

    /**
     * RC1-26: [JwtTokenVerifier] is CoSec's own verifier, so the tokens it verifies may use break-glass, and their
     * credential is named `jwt`; the same token through a verifier that wraps it is not CoSec's, and may not.
     */
    @Test
    fun onlyThisVerifiersTokensMayUseBreakGlass() {
        val token = signed {}
        fun authenticate(verifier: TokenVerifier) = runBlocking {
            (TokenAuthenticator(verifier).authenticate(Credentials(token, null)) as AuthnResult.Authenticated).principal
        }
        val principal = authenticate(jwtTokenVerifier)
        principal.breakGlass.assert().isTrue()
        principal.credential!!.method.assert().isEqualTo(Identity.Method.JWT)
        authenticate(object : TokenVerifier by jwtTokenVerifier {}).breakGlass.assert().isFalse()
    }

    /**
     * RC1-52, `IDN-10`: a token signed elsewhere can write an unpaired surrogate as a JSON escape (`"\uD800"`), which
     * java-jwt's own signing never does (it writes `?`). CoSec's verifier refuses it: `token-invalid`, never the
     * principal or tenant with `?` in its place.
     */
    @Test
    fun aTokenClaimingAnIdWithAnUnpairedSurrogateIsInvalid() {
        fun crafted(payload: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
            val body = encoder.encode(payload.encodeToByteArray())
            val signature = encoder.encodeToString(JwtFixture.ALGORITHM.sign(header, body))
            return "${String(header)}.${String(body)}.$signature"
        }
        val exp = Instant.now().plusSeconds(600).epochSecond
        val claims = listOf(
            """"sub":"alice\uD800","jti":"t1"""",
            """"sub":"alice","jti":"t1","tenantId":"beta\uD800"""",
            """"sub":"alice","jti":"t1\uDC00"""",
            """"sub":"alice","jti":"t1","roles":["admin\uD800"]""",
            """"sub":"alice","jti":"t1","policies":["p\uDC00"]""",
        )
        for (claim in claims) {
            val token = crafted("{$claim,\"exp\":$exp}")
            assertThrows(TokenVerificationException::class.java) { jwtTokenVerifier.verify(token) }
            val result = runBlocking { TokenAuthenticator(jwtTokenVerifier).authenticate(Credentials(token, null)) }
            (result as AuthnResult.Rejected).reason.assert().describedAs(
                claim
            ).isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
        }
    }

    /** RFC 0012 §3.5: `aud` is the token's app, and one value. */
    @Test
    fun audIsOneAppOrTheTokenIsInvalid() {
        fun crafted(aud: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
            val body = encoder.encode(
                """{"sub":"alice","jti":"t1","exp":${Instant.now().plusSeconds(
                    600
                ).epochSecond},"aud":$aud}""".encodeToByteArray()
            )
            val signature = encoder.encodeToString(JwtFixture.ALGORITHM.sign(header, body))
            return "${String(header)}.${String(body)}.$signature"
        }
        jwtTokenVerifier.verify(signed {}).app.assert().isNull()
        jwtTokenVerifier.verify(signed { withAudience("order") }).app.assert().isEqualTo("order")
        jwtTokenVerifier.verify(crafted("\"order\"")).app.assert().isEqualTo("order")
        jwtTokenVerifier.verify(crafted("[\"order\"]")).app.assert().isEqualTo("order")
        for (aud in listOf("[\"order\",\"billing\"]", "[]", "42", "null", "{}", "[1]", "\"\"", "\"*\"", "\"a:b\"")) {
            assertThrows(TokenVerificationException::class.java) { jwtTokenVerifier.verify(crafted(aud)) }
        }
    }

    /** `IDN-12`: an id beginning with `(` is CoSec's, whichever path verifies the token. */
    @Test
    fun aReservedSubjectIsInvalid() {
        for (subject in listOf("(0)", "(platform)", "(")) {
            val token = signed { withSubject(subject) }
            assertThrows(TokenVerificationException::class.java) { jwtTokenVerifier.verify(token) }
        }
        jwtTokenVerifier.verify(signed { withSubject("a(b)") }).principal.assert().isEqualTo("a(b)")
    }

    @Test
    fun aTokenWithoutASubjectOrIdIsInvalid() {
        val exp = Instant.now().plusSeconds(600)
        val noSubject = JWT.create().withJWTId("t1").withExpiresAt(exp).sign(JwtFixture.ALGORITHM)
        val noId = JWT.create().withSubject("alice").withExpiresAt(exp).sign(JwtFixture.ALGORITHM)
        assertThrows(TokenVerificationException::class.java) { jwtTokenVerifier.verify(noSubject) }
        assertThrows(TokenVerificationException::class.java) { jwtTokenVerifier.verify(noId) }
    }

    /** `may_act` only says who may act for the subject; the token is still the subject's own. */
    @Test
    fun mayActIsIgnored() {
        val token = signed { withClaim("may_act", mapOf("sub" to "agent")) }
        jwtTokenVerifier.verify(token).principal.assert().isEqualTo("alice")
    }

    @Test
    fun refresh() {
        val oldToken: CompositeToken = jwtTokenConverter.toToken(TokenSubject("alice", null))
        val newTokenPrincipal = jwtTokenVerifier.refresh(oldToken)
        newTokenPrincipal.principal.assert().isEqualTo(TokenSubject("alice", null).id)
        newTokenPrincipal.tenant.assert().isEqualTo(TokenSubject("alice", null).tenant)
    }

    @Test
    fun refreshWhenExpired() {
        val converter =
            JwtTokenConverter(
                JwtFixture.IDS,
                JwtFixture.ALGORITHM,
                Duration.ofMillis(1),
                Duration.ofMillis(1)
            )
        val oldToken: CompositeToken = converter.toToken(TokenSubject("alice", null))
        TimeUnit.SECONDS.sleep(1)
        assertThrows(TokenExpiredException::class.java) { jwtTokenVerifier.refresh(oldToken) }
    }

    @Test
    fun refreshWhenRefreshTokenBelongsToAnotherAccessToken() {
        // A valid refresh token paired with another valid access token is an invalid token (401), not a bad argument.
        val first: CompositeToken = jwtTokenConverter.toToken(TokenSubject("alice", null))
        val second: CompositeToken = jwtTokenConverter.toToken(TokenSubject("alice", null))
        val mismatched = SimpleCompositeToken(accessToken = second.accessToken, refreshToken = first.refreshToken)

        assertThrows(RefreshTokenMismatchException::class.java) {
            jwtTokenVerifier.refresh(mismatched)
        }
    }

    @Test
    fun refreshWhenAccessTokenSignatureIsForged() {
        val oldToken: CompositeToken = jwtTokenConverter.toToken(TokenSubject("alice", null))
        val legitAccessTokenId = Jwts.decode(oldToken.accessToken).id
        val forgedAccessToken = JWT.create()
            .withJWTId(legitAccessTokenId)
            .withSubject(TokenSubject("alice", null).id)
            .sign(Algorithm.HMAC256("attacker-secret"))
        val forgedToken = SimpleCompositeToken(
            accessToken = forgedAccessToken,
            refreshToken = oldToken.refreshToken,
        )

        assertThrownBy<TokenVerificationException> {
            jwtTokenVerifier.refresh(forgedToken)
        }
    }

    @Test
    fun refreshWhenAccessTokenExpiredButSignatureValid() {
        val converter = JwtTokenConverter(
            JwtFixture.IDS,
            JwtFixture.ALGORITHM,
            Duration.ofMillis(1),
            Duration.ofDays(7),
        )
        val oldToken: CompositeToken = converter.toToken(TokenSubject("alice", null))
        TimeUnit.SECONDS.sleep(1)

        val newTokenPrincipal = jwtTokenVerifier.refresh(oldToken)

        newTokenPrincipal.principal.assert().isEqualTo(TokenSubject("alice", null).id)
        newTokenPrincipal.tenant.assert().isEqualTo(TokenSubject("alice", null).tenant)
    }
}
