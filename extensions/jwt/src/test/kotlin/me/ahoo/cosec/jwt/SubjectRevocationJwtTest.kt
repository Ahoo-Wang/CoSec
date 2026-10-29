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

import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.RevocableTokenVerifier
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.spi.token.DefaultTokenRevoker
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * RFC 0004 §4.5.3 with real JWTs: revoking a principal's tokens rejects its access and refresh tokens issued before.
 */
class SubjectRevocationJwtTest {
    /** A real store for the test; entries never expire. */
    private class MapTokenStore : TokenStore {
        private val tokens = ConcurrentHashMap.newKeySet<String>()
        private val subjects = ConcurrentHashMap<String, Instant>()

        override fun revoke(tokenId: String, ttl: Duration) {
            tokens.add(tokenId)
        }

        override fun isRevoked(tokenId: String): Boolean = tokens.contains(tokenId)

        override fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) {
            subjects.merge("${tenant.value}/${principal.value}", issuedBefore) { a, b -> maxOf(a, b) }
        }

        override fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            subjects["${tenant.value}/${principal.value}"]
    }

    private val tokenStore = MapTokenStore()
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)

    // No skew: these tests place revocations to the millisecond of the tokens' `iat`.
    private val verifier = RevocableTokenVerifier(JwtTokenVerifier(JwtFixture.ALGORITHM), tokenStore, Duration.ZERO)
    private val revoker = DefaultTokenRevoker(verifier, tokenStore, Duration.ofDays(7))
    private val tenant = TenantId("tenant")

    private fun issue(id: String = "alice") =
        converter.toToken(
            SimpleTenantPrincipal(SimplePrincipal(id, roles = setOf("admin")), SimpleTenant(tenant.value))
        )

    private fun issuedAt(token: SimpleCompositeToken): Instant = Jwts.decode(token.accessToken).issuedAtAsInstant

    @Test
    fun revokesAccessAndRefreshTokensIssuedBefore() {
        val token = issue() as SimpleCompositeToken
        val bobToken = issue("bob")
        verifier.verify<TokenPrincipal>(token).id.assert().isEqualTo("alice")

        revoker.revokeSubject(tenant, PrincipalId("alice"), issuedAt(token).plusMillis(1))

        assertThrows<TokenRevokedException> { verifier.verify<TokenPrincipal>(token) }
        assertThrows<TokenRevokedException> { verifier.refresh<TokenPrincipal>(token) }
        assertDoesNotThrow { verifier.verify<TokenPrincipal>(bobToken) }
    }

    @Test
    fun acceptsTokensIssuedAtOrAfter() {
        val token = issue() as SimpleCompositeToken

        revoker.revokeSubject(tenant, PrincipalId("alice"), issuedAt(token))

        assertDoesNotThrow { verifier.verify<TokenPrincipal>(token) }
        assertDoesNotThrow { verifier.refresh<TokenPrincipal>(token) }
    }

    @Test
    fun revokingOneTokenStillWorks() {
        val token = issue()

        revoker.revoke(token)

        assertThrows<TokenRevokedException> { verifier.verify<TokenPrincipal>(token) }
    }
}
