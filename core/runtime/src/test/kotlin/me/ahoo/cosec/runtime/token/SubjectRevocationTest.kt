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

package me.ahoo.cosec.runtime.token

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.DefaultTokenRevoker
import me.ahoo.cosec.spi.token.SubjectRevocation
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
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
    private class FixedVerifier(var principal: VerifiedToken) : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = principal

        override fun refresh(token: CompositeToken): VerifiedToken = principal
    }

    private fun token(issuedAt: Instant?, tenantId: String? = tenant.value, id: String = alice.value): VerifiedToken =
        VerifiedToken("jti-$issuedAt", id, tenantId, null, issuedAt = issuedAt)

    private val verifier = FixedVerifier(token(now))

    // No skew: these tests place tokens to the millisecond of the revocation; the skew has its own test.
    private val revocableVerifier = RevocableTokenVerifier(verifier, tokenStore, Duration.ZERO)
    private val revoker = DefaultTokenRevoker(verifier, tokenStore, refreshValidity, Clock.fixed(now, ZoneOffset.UTC))
    private val accessToken = "access"
    private val compositeToken = SimpleCompositeToken("access", "refresh")

    private fun assertRevoked(principal: VerifiedToken) {
        verifier.principal = principal
        assertThrows<TokenRevokedException> { revocableVerifier.verify(accessToken) }
        assertThrows<TokenRevokedException> { revocableVerifier.refresh(compositeToken) }
    }

    private fun assertAccepted(principal: VerifiedToken) {
        verifier.principal = principal
        assertDoesNotThrow { revocableVerifier.verify(accessToken) }
        assertDoesNotThrow { revocableVerifier.refresh(compositeToken) }
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
        assertThrows<TokenRevokedException> { skewed.verify(accessToken) }
        assertThrows<TokenRevokedException> { skewed.refresh(compositeToken) }
        verifier.principal = token(now.plus(SubjectRevocation.DEFAULT_SKEW))
        assertDoesNotThrow { skewed.refresh(compositeToken) }
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
    fun aTokenWithoutIssuedAtIsRevoked() {
        val plain = VerifiedToken("plain", alice.value, null, null)
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
            override fun revoke(accessToken: String): VerifiedToken = throw UnsupportedOperationException()
        }

        assertThrows<UnsupportedOperationException> { revoker.revokeSubject(tenant, alice, now) }
    }
}
