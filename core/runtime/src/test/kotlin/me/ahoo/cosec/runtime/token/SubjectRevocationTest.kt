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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.SubjectRevocation
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * RFC 0004 §4.5.3: revoking every token of a principal, against a real store, judged by [TokenRevocation], which the
 * pipeline, refresh and logout share.
 */
class SubjectRevocationTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val tokenStore = InMemoryTokenStore()
    private val refreshValidity = Duration.ofDays(7)
    private val tenant = TenantId("tenant")
    private val alice = PrincipalId("alice")

    /** Answers with a fixed principal: the revoker only reads what the verifier verified. */
    private class FixedVerifier(var principal: VerifiedToken) : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = principal

        override fun refresh(token: CompositeToken): VerifiedToken = principal
    }

    private fun token(issuedAt: Instant?, tenantId: String? = tenant.value, id: String = alice.value): VerifiedToken =
        VerifiedToken("jti-$issuedAt", id, tenantId, null, issuedAt = issuedAt)

    private val verifier = FixedVerifier(token(now))
    private val accessToken = "access"

    // No skew: these tests place tokens to the millisecond of the revocation; the skew has its own test.
    private val revocation = TokenRevocation(tokenStore, Duration.ZERO)
    private val revoker =
        DefaultTokenRevoker(verifier, tokenStore, revocation, refreshValidity, Clock.fixed(now, ZoneOffset.UTC))

    private fun assertRevoked(principal: VerifiedToken, judgement: TokenRevocation = revocation) {
        assertThrows<TokenRevokedException> { runBlocking { judgement.check(principal) } }
    }

    private fun assertAccepted(principal: VerifiedToken, judgement: TokenRevocation = revocation) {
        runBlocking { judgement.check(principal) }
    }

    @Test
    fun tokensIssuedBeforeAreRevokedAndLaterOnesAccepted() = runTest {
        assertAccepted(token(now.minusSeconds(60)))

        revoker.revokeSubject(tenant, alice, now)

        assertRevoked(token(now.minusSeconds(60)))
        assertRevoked(token(now.minusMillis(1)))
        assertAccepted(token(now))
        assertAccepted(token(now.plusSeconds(1)))
    }

    /**
     * Review A7: a token issued within the skew after the revocation is revoked too. Refresh and the pipeline ask the
     * same judgement, so a token the pipeline refuses is never renewed.
     */
    @Test
    fun theSkewRevokesTokensIssuedJustAfterTheRevocation() = runTest {
        val skewed = TokenRevocation(tokenStore, SubjectRevocation.DEFAULT_SKEW)
        revoker.revokeSubject(tenant, alice, now)

        assertRevoked(token(now.plusSeconds(4)), skewed)
        assertAccepted(token(now.plus(SubjectRevocation.DEFAULT_SKEW)), skewed)
        assertThrows<IllegalArgumentException> { TokenRevocation(tokenStore, Duration.ofSeconds(-1)) }
    }

    @Test
    fun otherPrincipalsAndTenantsAreUntouched() = runTest {
        revoker.revokeSubject(tenant, alice, now)

        assertAccepted(token(now.minusSeconds(60), id = "bob"))
        assertAccepted(token(now.minusSeconds(60), tenantId = "other"))
    }

    @Test
    fun aPrincipalWithoutTenantIsInTheDefaultTenant() = runTest {
        revoker.revokeSubject(TenantId(CoSec.DEFAULT), alice, now)

        assertRevoked(token(now.minusSeconds(60), tenantId = null))
        assertAccepted(token(now.minusSeconds(60)))
    }

    @Test
    fun aTokenThatDoesNotSayWhenItWasIssuedIsRevoked() = runTest {
        assertAccepted(token(null))

        revoker.revokeSubject(tenant, alice, now)

        assertRevoked(token(null))
    }

    @Test
    fun aTokenWithoutIssuedAtIsRevoked() = runTest {
        val plain = VerifiedToken("plain", alice.value, null, null)
        assertAccepted(plain)

        revoker.revokeSubject(TenantId.DEFAULT, alice, now)

        assertRevoked(plain)
    }

    @Test
    fun aRevokedTokenIdStaysRevoked() = runTest {
        tokenStore.revoke("jti-$now", refreshValidity)

        assertRevoked(token(now))
    }

    @Test
    fun aRevokerRefusesATokenItsPrincipalsRevocationCovers() = runTest {
        revoker.revokeSubject(tenant, alice, now)
        verifier.principal = token(now.minusSeconds(60))

        assertThrows<TokenRevokedException> { revoker.revoke(accessToken) }
    }

    @Test
    fun theEntryOutlivesTheRefreshTokensItRevokes() = runTest {
        revoker.revokeSubject(tenant, alice, now)
        tokenStore.revokedSubjects.getValue(tenant.value to alice.value).second.assert().isEqualTo(refreshValidity)

        // tokens issued until a future issuedBefore live a refresh lifetime past it
        revoker.revokeSubject(tenant, alice, now.plus(Duration.ofHours(1)))
        tokenStore.revokedSubjects.getValue(tenant.value to alice.value).second
            .assert().isEqualTo(refreshValidity.plusHours(1))
        assertRevoked(token(now.plusSeconds(60)))
    }

    @Test
    fun anEarlierRevocationDoesNotShortenALaterOne() = runTest {
        revoker.revokeSubject(tenant, alice, now)
        revoker.revokeSubject(tenant, alice, now.minusSeconds(3600))

        assertRevoked(token(now.minusSeconds(60)))
    }

    @Test
    fun issuedBeforeDefaultsToNow() = runTest {
        val before = Instant.now()
        DefaultTokenRevoker(verifier, tokenStore, revocation, refreshValidity).revokeSubject(tenant, alice)

        tokenStore.subjectRevokedBefore(tenant, alice)!!.isBefore(before).assert().isFalse()
    }

    @Test
    fun aStoreThatCannotRecordItFails() = runTest {
        val writesTokensOnly = object : TokenStore {
            override suspend fun revoke(tokenId: String, ttl: Duration) = Unit
        }
        val revoker = DefaultTokenRevoker(verifier, writesTokensOnly, revocation, refreshValidity)

        assertThrows<UnsupportedOperationException> { revoker.revokeSubject(tenant, alice, now) }
    }

    @Test
    fun aRevokerThatCannotRevokeSubjectsFails() = runTest {
        val revoker = object : TokenRevoker {
            override suspend fun revoke(accessToken: String): VerifiedToken = throw UnsupportedOperationException()
        }

        assertThrows<UnsupportedOperationException> { revoker.revokeSubject(tenant, alice, now) }
    }
}
