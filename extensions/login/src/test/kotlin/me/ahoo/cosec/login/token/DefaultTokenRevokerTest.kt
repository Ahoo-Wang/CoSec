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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
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
import java.time.Duration
import java.time.Instant

class DefaultTokenRevokerTest {

    private val tokenVerifier = mockk<TokenVerifier>()
    private val tokenStore = InMemoryTokenStore()
    private val revocationTtl = Duration.ofDays(7)
    private val tokenRevoker = DefaultTokenRevoker(
        tokenVerifier,
        tokenStore,
        TokenRevocation(tokenStore),
        TokenFamilies(InMemoryTokenFamilyStore()),
        revocationTtl
    )

    @Test
    fun revoke() = runTest {
        val tokenPrincipal = VerifiedToken("tokenId", "alice", null, null)
        val accessToken = "accessToken"
        every { tokenVerifier.verify(accessToken) } returns tokenPrincipal

        val actual = tokenRevoker.revoke(accessToken)

        actual.assert().isSameAs(tokenPrincipal)
        tokenStore.revokedTokens.assert().containsEntry("tokenId", revocationTtl)
    }

    @Test
    fun revokeWhenTokenInvalid() = runTest {
        val accessToken = "invalid"
        every { tokenVerifier.verify(accessToken) } throws
            TokenVerificationException("Invalid token")

        assertThrows<TokenVerificationException> {
            tokenRevoker.revoke(accessToken)
        }
        tokenStore.revokedTokens.assert().isEmpty()
    }

    /** As before the store was read through [TokenRevocation]: a token already revoked is refused, not revoked again. */
    @Test
    fun revokeWhenAlreadyRevoked() = runTest {
        every { tokenVerifier.verify("accessToken") } returns VerifiedToken("tokenId", "alice", null, null)
        tokenRevoker.revoke("accessToken")

        assertThrows<TokenRevokedException> { tokenRevoker.revoke("accessToken") }
    }

    /** A logout is not stopped by a revocation store it cannot read: it records the revocation, which it can write. */
    @Test
    fun revokeWhenRevocationsCannotBeRead() = runTest {
        every { tokenVerifier.verify("accessToken") } returns VerifiedToken("tokenId", "alice", null, null)
        val unavailable = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = throw RevocationUnavailableException("down")

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
        }

        DefaultTokenRevoker(
            tokenVerifier,
            tokenStore,
            TokenRevocation(unavailable),
            TokenFamilies(InMemoryTokenFamilyStore()),
            revocationTtl
        ).revoke("accessToken")

        tokenStore.revokedTokens.assert().containsKey("tokenId")
    }
}
