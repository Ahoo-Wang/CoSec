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
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenNotYetValidException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * #1666, `IDN-29`: the verifier tolerates an issuer's clock ahead by the skew (`cosec.authentication.revocation.skew`)
 * for a token's `iat` and `nbf`, and never for its `exp`. Subject revocation judges with the same skew, so a token it
 * accepts early is still revoked when it was issued within the skew after a revocation (`MEM-4`).
 */
class IssuerSkewTest {
    private val verifier = JwtTokenVerifier(JwtFixture.SIGNING)

    private fun token(build: JWTCreator.Builder.() -> Unit): String = JWT.create()
        .withJWTId("t1")
        .withSubject("alice")
        .withClaim("tenant", "acme")
        .withExpiresAt(Instant.now().plusSeconds(3600))
        .apply(build)
        .sign(JwtFixture.ALGORITHM)

    @Test
    fun `an issue time within the skew ahead is accepted, beyond it refused as not valid yet`() {
        verifier.verify(token { withIssuedAt(Instant.now().plusSeconds(2)) }).principal.assert().isEqualTo("alice")
        val beyond = token { withIssuedAt(Instant.now().plusSeconds(9)) }
        assertThrownBy<TokenNotYetValidException> { verifier.verify(beyond) }.hasMessageContaining("iat")
    }

    @Test
    fun `a not-before time within the skew ahead is accepted, beyond it refused as not valid yet`() {
        verifier.verify(token { withNotBefore(Instant.now().plusSeconds(2)) }).principal.assert().isEqualTo("alice")
        val beyond = token { withNotBefore(Instant.now().plusSeconds(9)) }
        assertThrownBy<TokenNotYetValidException> { verifier.verify(beyond) }.hasMessageContaining("nbf")
    }

    @Test
    fun `an expiry has no skew`() {
        val expired = token { withExpiresAt(Instant.now().minusSeconds(1)) }
        assertThrownBy<TokenExpiredException> { verifier.verify(expired) }
        val lenient = JwtTokenVerifier(JwtFixture.SIGNING, Duration.ofMinutes(10))
        assertThrownBy<TokenExpiredException> { lenient.verify(expired) }
    }

    @Test
    fun `the skew is the verifier's, and never negative`() {
        val ahead = token { withIssuedAt(Instant.now().plusSeconds(2)) }
        val strict = JwtTokenVerifier(JwtFixture.SIGNING, Duration.ZERO)
        assertThrownBy<TokenNotYetValidException> { strict.verify(ahead) }
        val wide = JwtTokenVerifier(JwtFixture.SIGNING, Duration.ofSeconds(30))
        wide.verify(token { withIssuedAt(Instant.now().plusSeconds(20)) }).principal.assert().isEqualTo("alice")
        assertThrownBy<IllegalArgumentException> { JwtTokenVerifier(JwtFixture.SIGNING, Duration.ofSeconds(-1)) }
    }

    @Test
    fun `the authenticator names a token not valid yet, and a signature that fails stays invalid`() = runBlocking<Unit> {
        val authenticator = TokenAuthenticator(verifier, RevocationReader.NONE)
        val ahead = token { withIssuedAt(Instant.now().plusSeconds(9)) }
        val early = authenticator.authenticate(Credentials(ahead, null))
        (early as AuthnResult.Rejected).reason.assert().isEqualTo(AuthnResult.RejectReason.TOKEN_NOT_YET_VALID)
        val forged = token { withIssuedAt(Instant.now()) }.dropLast(2) + "xx"
        (authenticator.authenticate(Credentials(forged, null)) as AuthnResult.Rejected).reason
            .assert().isEqualTo(AuthnResult.RejectReason.TOKEN_INVALID)
    }

    /**
     * The two rules agree at the boundary: a principal revoked now, a token its issuer stamps 3 s ahead. The verifier
     * accepts the token's issue time (within the skew), and subject revocation, with the same skew, revokes it: it was
     * issued within the skew after the revocation. Beyond the skew, the verifier refuses it before revocation is asked.
     */
    @Test
    fun `a token accepted early is still revoked by a revocation it follows within the skew`() = runBlocking<Unit> {
        val revokedAt = Instant.now()
        val revocations = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = false

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant = revokedAt
        }
        val authenticator = TokenAuthenticator(verifier, revocations)
        val within = authenticator.authenticate(Credentials(token { withIssuedAt(revokedAt.plusSeconds(3)) }, null))
        (within as AuthnResult.Rejected).reason.assert().isEqualTo(AuthnResult.RejectReason.TOKEN_REVOKED)
        val beyond = authenticator.authenticate(Credentials(token { withIssuedAt(revokedAt.plusSeconds(9)) }, null))
        (beyond as AuthnResult.Rejected).reason.assert().isEqualTo(AuthnResult.RejectReason.TOKEN_NOT_YET_VALID)
    }
}
