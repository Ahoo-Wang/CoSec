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

package me.ahoo.cosec.runtime.token

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.SubjectRevocation
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.VerifiedToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * RFC 0004 §4.5.3, `MEM-4`: the one judgement of whether a verified token was revoked, which the pipeline, refresh and
 * logout share. The revocations are written to the port it reads; what a revoker records is `cosec-login`'s
 * `SubjectRevocationTest`.
 */
class TokenRevocationTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val tenant = TenantId("tenant")
    private val alice = PrincipalId("alice")

    /** What a store would answer: token ids, and principals revoked before an instant. */
    private class Revocations : RevocationReader {
        private val tokens = mutableSetOf<String>()
        private val subjects = mutableMapOf<Pair<TenantId, PrincipalId>, Instant>()

        fun revoke(tokenId: String) {
            tokens += tokenId
        }

        fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant) {
            subjects[tenant to principal] = issuedBefore
        }

        override suspend fun isRevoked(tokenId: String): Boolean = tokenId in tokens

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            subjects[tenant to principal]
    }

    private fun token(issuedAt: Instant?, tenantId: String? = tenant.value, id: String = alice.value): VerifiedToken =
        VerifiedToken("jti-$issuedAt", id, tenantId, null, issuedAt = issuedAt)

    private val revocations = Revocations()

    // No skew: these tests place tokens to the millisecond of the revocation; the skew has its own test.
    private val revocation = TokenRevocation(revocations, Duration.ZERO)

    private fun assertRevoked(principal: VerifiedToken, judgement: TokenRevocation = revocation) {
        assertThrows<TokenRevokedException> { runBlocking { judgement.check(principal) } }
    }

    private fun assertAccepted(principal: VerifiedToken, judgement: TokenRevocation = revocation) {
        runBlocking { judgement.check(principal) }
    }

    @Test
    fun tokensIssuedBeforeAreRevokedAndLaterOnesAccepted() = runTest {
        assertAccepted(token(now.minusSeconds(60)))

        revocations.revokeSubject(tenant, alice, now)

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
        val skewed = TokenRevocation(revocations, SubjectRevocation.DEFAULT_SKEW)
        revocations.revokeSubject(tenant, alice, now)

        assertRevoked(token(now.plusSeconds(4)), skewed)
        assertAccepted(token(now.plus(SubjectRevocation.DEFAULT_SKEW)), skewed)
        assertThrows<IllegalArgumentException> { TokenRevocation(revocations, Duration.ofSeconds(-1)) }
    }

    @Test
    fun otherPrincipalsAndTenantsAreUntouched() = runTest {
        revocations.revokeSubject(tenant, alice, now)

        assertAccepted(token(now.minusSeconds(60), id = "bob"))
        assertAccepted(token(now.minusSeconds(60), tenantId = "other"))
    }

    @Test
    fun aPrincipalWithoutTenantIsInTheDefaultTenant() = runTest {
        revocations.revokeSubject(TenantId.DEFAULT, alice, now)

        assertRevoked(token(now.minusSeconds(60), tenantId = null))
        assertAccepted(token(now.minusSeconds(60)))
    }

    @Test
    fun aTokenThatDoesNotSayWhenItWasIssuedIsRevoked() = runTest {
        assertAccepted(token(null))

        revocations.revokeSubject(tenant, alice, now)

        assertRevoked(token(null))
    }

    @Test
    fun aTokenWithoutIssuedAtIsRevoked() = runTest {
        val plain = VerifiedToken("plain", alice.value, null, null)
        assertAccepted(plain)

        revocations.revokeSubject(TenantId.DEFAULT, alice, now)

        assertRevoked(plain)
    }

    @Test
    fun aRevokedTokenIdStaysRevoked() = runTest {
        revocations.revoke("jti-$now")

        assertRevoked(token(now))
    }
}
