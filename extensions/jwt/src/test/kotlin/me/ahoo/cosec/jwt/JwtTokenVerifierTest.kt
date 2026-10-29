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
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.api.token.TokenTenantPrincipal
import me.ahoo.cosec.login.token.RefreshTokenMismatchException
import me.ahoo.cosec.runtime.token.SimpleAccessToken
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.spi.token.DelegationUnsupportedException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class JwtTokenVerifierTest {
    var jwtTokenConverter = JwtTokenConverter(MockIdGenerator.INSTANCE, JwtFixture.ALGORITHM)
    private val jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    @Test
    fun verify() {
        val token: CompositeToken = jwtTokenConverter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        val principal: TokenTenantPrincipal = jwtTokenVerifier.verify(token)
        principal.name.assert().isEqualTo(CoSecPrincipal.ANONYMOUS_ID)
    }

    @Test
    fun verifyWhenExpiresAtMissing() {
        val token = JWT.create().withSubject("subject").sign(JwtFixture.ALGORITHM)
        val thrown = assertThrows(TokenVerificationException::class.java) {
            jwtTokenVerifier.verify<TokenPrincipal>(SimpleAccessToken(token))
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
            jwtTokenVerifier.verify<TokenPrincipal>(SimpleAccessToken(delegated))
        }
        // Any value counts: an actor CoSec cannot read is still an actor.
        assertThrownBy<DelegationUnsupportedException> {
            jwtTokenVerifier.verify<TokenPrincipal>(SimpleAccessToken(signed { withNullClaim("act") }))
        }
        assertThrownBy<DelegationUnsupportedException> {
            jwtTokenVerifier.verify<TokenPrincipal>(SimpleAccessToken(signed { withClaim("act", "agent") }))
        }
    }

    @Test
    fun refreshingADelegationTokenIsRefused() {
        val access = signed { withClaim("act", mapOf("sub" to "agent")) }
        val refresh = JWT.create().withSubject("t1").withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(JwtFixture.ALGORITHM)
        assertThrownBy<DelegationUnsupportedException> {
            jwtTokenVerifier.refresh<TokenPrincipal>(SimpleCompositeToken(access, refresh))
        }
    }

    /** A malformed access token is an invalid token, not an unmodelled error. */
    @Test
    fun refreshingAMalformedAccessTokenIsInvalid() {
        val refresh = JWT.create().withSubject("t1").withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(JwtFixture.ALGORITHM)
        val thrown = assertThrows(TokenVerificationException::class.java) {
            jwtTokenVerifier.refresh<TokenPrincipal>(SimpleCompositeToken("not-a-jwt", refresh))
        }
        assertFalse(thrown is TokenExpiredException)
    }

    /** `may_act` only says who may act for the subject; the token is still the subject's own. */
    @Test
    fun mayActIsIgnored() {
        val token = signed { withClaim("may_act", mapOf("sub" to "agent")) }
        jwtTokenVerifier.verify<TokenPrincipal>(SimpleAccessToken(token)).id.assert().isEqualTo("alice")
    }

    @Test
    fun refresh() {
        val oldToken: CompositeToken = jwtTokenConverter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        val newTokenPrincipal = jwtTokenVerifier.refresh<TokenTenantPrincipal>(oldToken)
        newTokenPrincipal.id.assert().isEqualTo(SimpleTenantPrincipal.ANONYMOUS.id)
        newTokenPrincipal.tenant.tenantId.assert().isEqualTo(SimpleTenantPrincipal.ANONYMOUS.tenant.tenantId)
    }

    @Test
    fun refreshWhenExpired() {
        val converter =
            JwtTokenConverter(
                MockIdGenerator.INSTANCE,
                JwtFixture.ALGORITHM,
                Duration.ofMillis(1),
                Duration.ofMillis(1)
            )
        val oldToken: CompositeToken = converter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        TimeUnit.SECONDS.sleep(1)
        assertThrows(TokenExpiredException::class.java) { jwtTokenVerifier.refresh<TokenPrincipal>(oldToken) }
    }

    @Test
    fun refreshWhenRefreshTokenBelongsToAnotherAccessToken() {
        // A valid refresh token paired with another valid access token is an invalid token (401), not a bad argument.
        val first: CompositeToken = jwtTokenConverter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        val second: CompositeToken = jwtTokenConverter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        val mismatched = SimpleCompositeToken(accessToken = second.accessToken, refreshToken = first.refreshToken)

        assertThrows(RefreshTokenMismatchException::class.java) {
            jwtTokenVerifier.refresh<TokenPrincipal>(mismatched)
        }
    }

    @Test
    fun refreshWhenAccessTokenSignatureIsForged() {
        val oldToken: CompositeToken = jwtTokenConverter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        val legitAccessTokenId = Jwts.decode(oldToken.accessToken).id
        val forgedAccessToken = JWT.create()
            .withJWTId(legitAccessTokenId)
            .withSubject(SimpleTenantPrincipal.ANONYMOUS.id)
            .sign(Algorithm.HMAC256("attacker-secret"))
        val forgedToken = SimpleCompositeToken(
            accessToken = forgedAccessToken,
            refreshToken = oldToken.refreshToken,
        )

        assertThrownBy<TokenVerificationException> {
            jwtTokenVerifier.refresh<TokenPrincipal>(forgedToken)
        }
    }

    @Test
    fun refreshWhenAccessTokenExpiredButSignatureValid() {
        val converter = JwtTokenConverter(
            MockIdGenerator.INSTANCE,
            JwtFixture.ALGORITHM,
            Duration.ofMillis(1),
            Duration.ofDays(7),
        )
        val oldToken: CompositeToken = converter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        TimeUnit.SECONDS.sleep(1)

        val newTokenPrincipal = jwtTokenVerifier.refresh<TokenTenantPrincipal>(oldToken)

        newTokenPrincipal.id.assert().isEqualTo(SimpleTenantPrincipal.ANONYMOUS.id)
        newTokenPrincipal.tenant.tenantId.assert().isEqualTo(SimpleTenantPrincipal.ANONYMOUS.tenant.tenantId)
    }
}
