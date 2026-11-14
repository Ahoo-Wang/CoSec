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
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.DefaultAuthenticationProvider
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.RefreshTokenMismatchException
import me.ahoo.cosec.login.token.SimpleRefreshTokenAuthentication
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.DefaultTokenRevoker
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.runtime.token.TokenFamilies
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** RFC 0009 with real JWTs: a login starts a session both tokens carry; refresh rotates it; logout ends it. */
class RefreshTokenFamiliesJwtTest {
    private class Pair(token: CompositeToken) : RefreshTokenCredentials {
        override val accessToken: String = token.accessToken
        override val refreshToken: String = token.refreshToken
    }

    private class Revoked : TokenStore, RevocationReader {
        val tokens: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override suspend fun revoke(tokenId: String, ttl: Duration) {
            tokens += tokenId
        }

        override suspend fun isRevoked(tokenId: String): Boolean = tokenId in tokens

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
    }

    private val store = InMemoryTokenFamilyStore()
    private val families = TokenFamilies(store, Duration.ofDays(30), Duration.ofSeconds(5))
    private val revoked = Revoked()
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM)
    private val refresh = SimpleRefreshTokenAuthentication(verifier, TokenRevocation(revoked), families)
    private val revoker = DefaultTokenRevoker(verifier, revoked, TokenRevocation(revoked), families, Duration.ofDays(7))
    private val tokens = TokenCompositeAuthentication(
        CompositeAuthentication(DefaultAuthenticationProvider().apply { register(refresh) }),
        converter,
        families,
    )

    private fun login(subject: TokenSubject = TokenSubject("alice", null, tenant = "acme")): CompositeToken =
        converter.toToken(subject.inSession(runBlocking { families.start(TenantId("acme"), PrincipalId(subject.id)) }))

    private fun refreshed(token: CompositeToken): CompositeToken = runBlocking {
        tokens.authenticateAsToken(
            Pair(token)
        )
    }

    @Test
    fun `both tokens of a pair carry its session, and a refresh carries the next generation`() {
        val first = login()
        val access = verifier.verify(first.accessToken).session!!
        Jwts.sessionOf(Jwts.decode(first.refreshToken)).assert().isEqualTo(access)
        JWT.decode(first.accessToken).getClaim("sid").asString().assert().isEqualTo(access.id)
        JWT.decode(first.accessToken).getClaim("auth_time").asLong().assert().isEqualTo(access.authTime.epochSecond)

        val second = refreshed(first)
        val next = verifier.verify(second.accessToken).session!!
        next.id.assert().isEqualTo(access.id)
        next.authTime.assert().isEqualTo(access.authTime)
        next.generation.assert().isEqualTo(2L)
    }

    /**
     * IDN-15, IDN-16: a refresh retried with the pair just replaced (its response was lost) is answered with a pair of
     * the current generation; only the current one moves the session on, and a pair two generations back ends it.
     */
    @Test
    fun `a retried refresh heals, and only the current pair moves the session on`() {
        val first = login()
        val second = refreshed(first)
        val retried = refreshed(first)
        verifier.verify(retried.accessToken).session!!.assert().isEqualTo(verifier.verify(second.accessToken).session)
        verifier.verify(refreshed(retried).accessToken).session!!.generation.assert().isEqualTo(3L)
        assertThrows<TokenRevokedException> { refreshed(first) }
        assertThrows<TokenRevokedException> { refreshed(second) }
    }

    /** IDN-17: no token of a pair outlives its session. */
    @Test
    fun `a pair never outlives its session`() {
        val subject = TokenSubject("alice", null, tenant = "acme")
        val signedIn = Instant.now().minus(Duration.ofDays(30)).plus(Duration.ofHours(1))
        val issue = runBlocking { families.start(TenantId("acme"), PrincipalId("alice"), signedIn) }
        val pair = converter.toToken(subject.inSession(issue))
        JWT.decode(pair.refreshToken).expiresAtAsInstant.isAfter(issue.notAfter).assert().isFalse()
        JWT.decode(pair.accessToken).expiresAtAsInstant.isAfter(issue.notAfter).assert().isFalse()
    }

    /** IDN-18: a logout ends the session from the access token, or from the pair once that has expired. */
    @Test
    fun `logout ends every pair of the session`() = runBlocking<Unit> {
        val first = login()
        val second = refreshed(first)
        revoker.revoke(second.accessToken)
        assertThrows<TokenRevokedException> { refreshed(second) }

        val expiring = JwtTokenConverter(
            JwtFixture.IDS,
            JwtFixture.ALGORITHM,
            Duration.ofSeconds(-1),
            Duration.ofDays(7)
        )
        val old = expiring.toToken(
            TokenSubject("alice", null, tenant = "acme")
                .inSession(families.start(TenantId("acme"), PrincipalId("alice")))
        )
        assertThrows<TokenVerificationException> { revoker.revoke(old.accessToken) }
        revoker.revoke(old)
        assertThrows<TokenRevokedException> { refreshed(old) }
        // The pair must be bound, as a refresh requires.
        assertThrows<RefreshTokenMismatchException> {
            revoker.revoke(SimpleCompositeToken(old.accessToken, first.refreshToken))
        }
    }

    @Test
    fun `a pair without a session, or a refresh token of another session, does not refresh`() {
        val bare = converter.toToken(TokenSubject("alice", null, tenant = "acme"))
        assertThrows<TokenVerificationException> { refreshed(bare) }
        val one = login()
        val other = login()
        assertThrows<TokenVerificationException> {
            refreshed(SimpleCompositeToken(one.accessToken, other.refreshToken))
        }
    }
}
