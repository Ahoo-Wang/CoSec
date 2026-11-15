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
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class RefreshTokenAuthenticationTest {
    var jwtTokenConverter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private var jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)
    private val families = TokenFamilies(InMemoryTokenFamilyStore())

    /** A pair as a login issues it: in a session of its own. */
    private fun issue(subject: TokenSubject): CompositeToken = jwtTokenConverter.toToken(
        subject.inSession(runBlocking { families.start(TenantId(subject.tenant ?: "(0)"), PrincipalId(subject.id)) })
    )

    private fun credentials(access: CompositeToken, refresh: CompositeToken = access) = object : RefreshTokenCredentials {
        override val accessToken: String
            get() = access.accessToken
        override val refreshToken: String
            get() = refresh.refreshToken
    }

    @Test
    fun authenticate(): Unit = runBlocking {
        val refreshTokenAuthentication = RefreshTokenAuthentication(
            jwtTokenVerifier,
            TokenRevocation(RevocationReader.NONE),
            families,
        )
        refreshTokenAuthentication.credentialsType.assert().isEqualTo(RefreshTokenCredentials::class.java)
        val oldToken: CompositeToken = issue(TokenSubject("alice", null))

        val subject = refreshTokenAuthentication.authenticate(credentials(oldToken))
        subject.id.assert().isEqualTo(TokenSubject("alice", null).id)
        subject.tenant.assert().isEqualTo(TokenSubject("alice", null).tenant)
    }

    /** The application re-reads the user: the new token says what the user holds now, for the token's own principal. */
    @Test
    fun `a membership refresh decides what the new token holds, and nothing else`(): Unit = runBlocking {
        val asked = mutableListOf<String>()
        val now = MembershipRefresh { token ->
            asked += token.principal
            TenantMembership(roles = setOf("auditor"), attributes = mapOf("department" to "audit"))
        }
        val authentication =
            RefreshTokenAuthentication(jwtTokenVerifier, TokenRevocation(RevocationReader.NONE), families, now)
        val old = issue(TokenSubject("alice", "order", tenant = "acme", roles = setOf("clerk")))

        val subject = authentication.authenticate(credentials(old))
        asked.assert().containsExactly("alice")
        subject.id.assert().isEqualTo("alice")
        subject.tenant.assert().isEqualTo("acme")
        subject.app.assert().isEqualTo("order")
        subject.roles.assert().containsExactly("auditor")
        subject.attributes.assert().isEqualTo(mapOf("department" to "audit"))
        subject.session.assert().isNotNull()
        // Without an answer, the token's claims are kept.
        val kept = RefreshTokenAuthentication(jwtTokenVerifier, TokenRevocation(RevocationReader.NONE), families)
            .authenticate(credentials(jwtTokenConverter.toToken(subject)))
        kept.roles.assert().containsExactly("auditor")
    }

    /** A refresh never changes the app, and a refused refresh spends no generation: the same pair refreshes after. */
    @Test
    fun `a membership refresh for another app is refused before the session moves`(): Unit = runBlocking {
        val old = issue(TokenSubject("alice", "order", tenant = "acme"))
        assertThrows<IllegalArgumentException> {
            RefreshTokenAuthentication(jwtTokenVerifier, TokenRevocation(RevocationReader.NONE), families) {
                TenantMembership(app = "billing")
            }.authenticate(credentials(old))
        }
        RefreshTokenAuthentication(jwtTokenVerifier, TokenRevocation(RevocationReader.NONE), families) {
            TenantMembership(app = "order")
        }.authenticate(credentials(old)).app.assert().isEqualTo("order")
    }

    /** The revocation check comes before the application is asked: a revoked token never reaches it. */
    @Test
    fun `a revoked token is refused before the membership refresh is asked`(): Unit = runBlocking {
        val old = issue(TokenSubject("alice", null))
        val revoked = Jwts.decode(old.accessToken).id
        val revocations = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = tokenId == revoked

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
        }
        var asked = false
        assertThrows<TokenRevokedException> {
            RefreshTokenAuthentication(jwtTokenVerifier, TokenRevocation(revocations), families) {
                asked = true
                null
            }.authenticate(credentials(old))
        }
        asked.assert().isFalse()
    }

    @Test
    fun `a refresh token of another token is refused`(): Unit = runBlocking {
        val authentication =
            RefreshTokenAuthentication(jwtTokenVerifier, TokenRevocation(RevocationReader.NONE), families)
        val alice = issue(TokenSubject("alice", null))
        val bob = issue(TokenSubject("bob", null))

        assertThrows<TokenVerificationException> { authentication.authenticate(credentials(alice, bob)) }
    }

    /**
     * The verifier is pure computation and runs on the caller's thread; the revocation read suspends. A revoked access
     * token is not refreshed.
     */
    @Test
    fun `the refresh verifies on the caller's thread and refuses a revoked token`(): Unit = runBlocking {
        var verifying: Thread? = null
        val verifier = object : TokenVerifier by jwtTokenVerifier {
            override fun refresh(token: CompositeToken): VerifiedToken {
                verifying = Thread.currentThread()
                return jwtTokenVerifier.refresh(token)
            }
        }
        val old = issue(TokenSubject("alice", null))
        val revoked = Jwts.decode(old.accessToken).id
        val revocations = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = tokenId == revoked

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
        }

        assertThrows<TokenRevokedException> {
            RefreshTokenAuthentication(
                verifier,
                TokenRevocation(revocations),
                families
            ).authenticate(credentials(old))
        }
        verifying.assert().isSameAs(Thread.currentThread())
    }

    /**
     * IDN-14 (round-2 review H1): a token is never issued on a revocation read that failed. Read as "not revoked", a
     * logged-out or revoked pair refreshed during a Redis outage would become a pair no revocation names.
     */
    @Test
    fun `a refresh is refused when the revocations cannot be read`(): Unit = runBlocking {
        val unavailable = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = throw RevocationUnavailableException("down")

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
                throw RevocationUnavailableException("down")
        }
        val old = issue(TokenSubject("alice", null))

        assertThrows<RevocationUnavailableException> {
            RefreshTokenAuthentication(
                jwtTokenVerifier,
                TokenRevocation(unavailable),
                families,
            ).authenticate(credentials(old))
        }
    }
}
