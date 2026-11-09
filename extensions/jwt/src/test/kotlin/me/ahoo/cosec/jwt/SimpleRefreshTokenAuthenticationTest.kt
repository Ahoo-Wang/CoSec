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
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.SimpleRefreshTokenAuthentication
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SimpleRefreshTokenAuthenticationTest {
    var jwtTokenConverter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private var jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    private fun credentials(access: CompositeToken, refresh: CompositeToken = access) = object : RefreshTokenCredentials {
        override val accessToken: String
            get() = access.accessToken
        override val refreshToken: String
            get() = refresh.refreshToken
    }

    @Test
    fun authenticate(): Unit = runBlocking {
        val refreshTokenAuthentication = SimpleRefreshTokenAuthentication(jwtTokenVerifier)
        refreshTokenAuthentication.supportCredentials.assert().isEqualTo(RefreshTokenCredentials::class.java)
        val oldToken: CompositeToken = jwtTokenConverter.toToken(TokenSubject("alice", null))

        val subject = refreshTokenAuthentication.authenticate(credentials(oldToken))
        subject.id.assert().isEqualTo(TokenSubject("alice", null).id)
        subject.tenant.assert().isEqualTo(TokenSubject("alice", null).tenant)
    }

    @Test
    fun `a refresh token of another token is refused`(): Unit = runBlocking {
        val authentication = SimpleRefreshTokenAuthentication(jwtTokenVerifier)
        val alice = jwtTokenConverter.toToken(TokenSubject("alice", null))
        val bob = jwtTokenConverter.toToken(TokenSubject("bob", null))

        assertThrows<TokenVerificationException> { authentication.authenticate(credentials(alice, bob)) }
    }

    /** A verifier reads the revocation store over blocking I/O: never on the caller's thread, an event loop in WebFlux. */
    @Test
    fun `the refresh runs off the caller's thread`(): Unit = runBlocking {
        var verifying: Thread? = null
        val verifier = object : TokenVerifier by jwtTokenVerifier {
            override fun refresh(token: CompositeToken): VerifiedToken {
                verifying = Thread.currentThread()
                return jwtTokenVerifier.refresh(token)
            }
        }

        SimpleRefreshTokenAuthentication(verifier)
            .authenticate(credentials(jwtTokenConverter.toToken(TokenSubject("alice", null))))

        verifying.assert().isNotNull().isNotSameAs(Thread.currentThread())
    }
}
