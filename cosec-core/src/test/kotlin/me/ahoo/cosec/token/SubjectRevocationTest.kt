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

package me.ahoo.cosec.token

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * RFC 0004 §4.5.3: revoking every token of a principal, against a real store.
 */
class SubjectRevocationTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val tokenStore = InMemoryTokenStore()
    private val refreshValidity = Duration.ofDays(7)
    private val tenant = TenantId("tenant")
    private val alice = PrincipalId("alice")

    /** Answers with a fixed principal: the decorator under test only reads what the delegate verified. */
    private class FixedVerifier(var principal: TokenPrincipal) : TokenVerifier {
        @Suppress("UNCHECKED_CAST")
        override fun <T : TokenPrincipal> verify(accessToken: AccessToken): T = principal as T

        @Suppress("UNCHECKED_CAST")
        override fun <T : TokenPrincipal> refresh(token: CompositeToken): T = principal as T
    }

    private fun token(issuedAt: Instant?, tenantId: String? = tenant.value, id: String = alice.value): TokenPrincipal {
        val tokenPrincipal = SimpleTokenPrincipal("jti-$issuedAt", SimplePrincipal(id), issuedAt)
        return if (tenantId == null) {
            tokenPrincipal
        } else {
            SimpleTokenTenantPrincipal(tokenPrincipal, SimpleTenant(tenantId))
        }
    }

    private val verifier = FixedVerifier(token(now))

    // No skew: these tests place tokens to the millisecond of the revocation; the skew has its own test.
    private val revocableVerifier = RevocableTokenVerifier(verifier, tokenStore, Duration.ZERO)
    private val revoker = DefaultTokenRevoker(verifier, tokenStore, refreshValidity, Clock.fixed(now, ZoneOffset.UTC))
    private val accessToken = SimpleAccessToken("access")
    private val compositeToken = SimpleCompositeToken("access", "refresh")

    private fun assertRevoked(principal: TokenPrincipal) {
        verifier.principal = principal
        assertThrows<TokenRevokedException> { revocableVerifier.verify<TokenPrincipal>(accessToken) }
        assertThrows<TokenRevokedException> { revocableVerifier.refresh<TokenPrincipal>(compositeToken) }
    }

    private fun assertAccepted(principal: TokenPrincipal) {
        verifier.principal = principal
        assertDoesNotThrow { revocableVerifier.verify<TokenPrincipal>(accessToken) }
        assertDoesNotThrow { revocableVerifier.refresh<TokenPrincipal>(compositeToken) }
    }

    @Test
    fun tokensIssuedBeforeAreRevokedAndLaterOnesAccepted() {
        assertAccepted(token(now.minusSeconds(60)))

        revoker.revokeSubject(tenant, alice, now)

        assertRevoked(token(now.minusSeconds(60)))
        assertRevoked(token(now.minusMillis(1)))
        assertAccepted(token(now))
        assertAccepted(token(now.plusSeconds(1)))
    }

    /** Review A7: refresh revokes within the skew exactly as the pipeline does, so a refused token is not renewed. */
    @Test
    fun theSkewRevokesTokensIssuedJustAfterTheRevocation() {
        val skewed = RevocableTokenVerifier(verifier, tokenStore, SubjectRevocation.DEFAULT_SKEW)
        revoker.revokeSubject(tenant, alice, now)

        verifier.principal = token(now.plusSeconds(4))
        assertThrows<TokenRevokedException> { skewed.verify<TokenPrincipal>(accessToken) }
        assertThrows<TokenRevokedException> { skewed.refresh<TokenPrincipal>(compositeToken) }
        verifier.principal = token(now.plus(SubjectRevocation.DEFAULT_SKEW))
        assertDoesNotThrow { skewed.refresh<TokenPrincipal>(compositeToken) }
        assertThrows<IllegalArgumentException> { RevocableTokenVerifier(verifier, tokenStore, Duration.ofSeconds(-1)) }
    }

    @Test
    fun otherPrincipalsAndTenantsAreUntouched() {
        revoker.revokeSubject(tenant, alice, now)

        assertAccepted(token(now.minusSeconds(60), id = "bob"))
        assertAccepted(token(now.minusSeconds(60), tenantId = "other"))
    }

    @Test
    fun aPrincipalWithoutTenantIsInTheDefaultTenant() {
        revoker.revokeSubject(TenantId(CoSec.DEFAULT), alice, now)

        assertRevoked(token(now.minusSeconds(60), tenantId = null))
        assertAccepted(token(now.minusSeconds(60)))
    }

    @Test
    fun aTokenThatDoesNotSayWhenItWasIssuedIsRevoked() {
        assertAccepted(token(null))

        revoker.revokeSubject(tenant, alice, now)

        assertRevoked(token(null))
    }

    @Test
    fun aTokenPrincipalWithoutIssuedAtIsRevoked() {
        val plain = object : TokenPrincipal, CoSecPrincipal by SimplePrincipal(alice.value) {
            override val tokenId: String = "plain"
        }
        assertAccepted(plain)

        revoker.revokeSubject(TenantId.DEFAULT, alice, now)

        assertRevoked(plain)
    }

    @Test
    fun aRevokedTokenIdStaysRevoked() {
        tokenStore.revoke("jti-$now", refreshValidity)

        assertRevoked(token(now))
    }

    @Test
    fun theEntryOutlivesTheRefreshTokensItRevokes() {
        revoker.revokeSubject(tenant, alice, now)
        tokenStore.revokedSubjects.getValue(tenant.value to alice.value).second.assert().isEqualTo(refreshValidity)

        // tokens issued until a future issuedBefore live a refresh lifetime past it
        revoker.revokeSubject(tenant, alice, now.plus(Duration.ofHours(1)))
        tokenStore.revokedSubjects.getValue(tenant.value to alice.value).second
            .assert().isEqualTo(refreshValidity.plusHours(1))
        assertRevoked(token(now.plusSeconds(60)))
    }

    @Test
    fun anEarlierRevocationDoesNotShortenALaterOne() {
        revoker.revokeSubject(tenant, alice, now)
        revoker.revokeSubject(tenant, alice, now.minusSeconds(3600))

        assertRevoked(token(now.minusSeconds(60)))
    }

    @Test
    fun issuedBeforeDefaultsToNow() {
        val before = Instant.now()
        DefaultTokenRevoker(verifier, tokenStore, refreshValidity).revokeSubject(tenant, alice)

        tokenStore.subjectRevokedBefore(tenant, alice)!!.isBefore(before).assert().isFalse()
    }

    @Test
    fun aStoreThatCannotRecordItFails() {
        val revoker = DefaultTokenRevoker(verifier, TokenStore.NoOp, refreshValidity)

        assertThrows<UnsupportedOperationException> { revoker.revokeSubject(tenant, alice, now) }
    }

    @Test
    fun aRevokerThatCannotRevokeSubjectsFails() {
        val revoker = object : TokenRevoker {
            override fun revoke(accessToken: AccessToken): TokenPrincipal = throw UnsupportedOperationException()
        }

        assertThrows<UnsupportedOperationException> { revoker.revokeSubject(tenant, alice, now) }
    }
}
