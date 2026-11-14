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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.SimpleRefreshTokenAuthentication
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.token.DefaultTokenRevoker
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * RFC 0004 §4.5.3 with real JWTs: revoking a principal's tokens rejects its access tokens in the pipeline and its
 * refresh, which judge revocation alike ([TokenRevocation]).
 */
class SubjectRevocationJwtTest {
    /** A real store for the test, and its reader; entries never expire. */
    private class MapTokenStore : TokenStore, RevocationReader {
        private val tokens = ConcurrentHashMap.newKeySet<String>()
        private val subjects = ConcurrentHashMap<String, Instant>()

        override suspend fun revoke(tokenId: String, ttl: Duration) {
            tokens.add(tokenId)
        }

        override suspend fun isRevoked(tokenId: String): Boolean = tokens.contains(tokenId)

        override suspend fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) {
            subjects.merge("${tenant.value}/${principal.value}", issuedBefore) { a, b -> maxOf(a, b) }
        }

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            subjects["${tenant.value}/${principal.value}"]
    }

    private class Pair(token: SimpleCompositeToken) : RefreshTokenCredentials {
        override val accessToken: String = token.accessToken
        override val refreshToken: String = token.refreshToken
    }

    private val tokenStore = MapTokenStore()
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    // No skew: these tests place revocations to the millisecond of the tokens' `iat`.
    private val revocation = TokenRevocation(tokenStore, Duration.ZERO)
    private val authenticator = TokenAuthenticator(verifier, tokenStore, revocationSkew = Duration.ZERO)
    private val refresh = SimpleRefreshTokenAuthentication(verifier, revocation)
    private val revoker = DefaultTokenRevoker(verifier, tokenStore, revocation, Duration.ofDays(7))
    private val tenant = TenantId("tenant")

    private fun issue(id: String = "alice") =
        converter.toToken(
            TokenSubject(id, null, tenant = tenant.value, roles = setOf("admin"))
        ) as SimpleCompositeToken

    private fun issuedAt(token: SimpleCompositeToken): Instant = Jwts.decode(token.accessToken).issuedAtAsInstant

    private fun authenticate(token: SimpleCompositeToken): AuthnResult =
        runBlocking { authenticator.authenticate(Credentials(token.accessToken, null)) }

    @Test
    fun revokesAccessAndRefreshTokensIssuedBefore() = runBlocking<Unit> {
        val token = issue()
        val bobToken = issue("bob")
        authenticate(token).assert().isInstanceOf(AuthnResult.Authenticated::class.java)

        revoker.revokeSubject(tenant, PrincipalId("alice"), issuedAt(token).plusMillis(1))

        (authenticate(token) as AuthnResult.Rejected).reason.assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        assertThrows<TokenRevokedException> { runBlocking { refresh.authenticate(Pair(token)) } }
        authenticate(bobToken).assert().isInstanceOf(AuthnResult.Authenticated::class.java)
    }

    @Test
    fun acceptsTokensIssuedAtOrAfter() = runBlocking<Unit> {
        val token = issue()

        revoker.revokeSubject(tenant, PrincipalId("alice"), issuedAt(token))

        authenticate(token).assert().isInstanceOf(AuthnResult.Authenticated::class.java)
        refresh.authenticate(Pair(token)).id.assert().isEqualTo("alice")
    }

    @Test
    fun revokingOneTokenStillWorks() = runBlocking<Unit> {
        val token = issue()

        revoker.revoke(token.accessToken)

        (authenticate(token) as AuthnResult.Rejected).reason.assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        assertThrows<TokenRevokedException> { runBlocking { refresh.authenticate(Pair(token)) } }
        assertThrows<TokenRevokedException> { runBlocking { revoker.revoke(token.accessToken) } }
    }
}
