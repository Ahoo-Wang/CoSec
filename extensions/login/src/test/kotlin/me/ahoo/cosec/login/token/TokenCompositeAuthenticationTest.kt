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

package me.ahoo.cosec.login.token

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.Credentials
import me.ahoo.cosec.login.DefaultAuthenticationProvider
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.runtime.token.TokenFamilies
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TokenCompositeAuthenticationTest {
    private val compositeToken = SimpleCompositeToken("accessToken", "refreshToken")
    private val tokenConverter = mockk<TokenConverter> {
        every { toToken(any()) } returns compositeToken
    }
    private val credentials = mockk<Credentials>()
    private val store = InMemoryTokenFamilyStore()
    private val families = TokenFamilies(store)

    /** RFC 0009: a login starts a session, at generation 1, which the issued pair carries and its end bounds. */
    @Test
    fun `a login starts a session`() = runTest {
        val issued = slot<TokenSubject>()
        every { tokenConverter.toToken(capture(issued)) } returns compositeToken
        tokensBy(mockk { coEvery { authenticate(any()) } returns TokenSubject("alice", null, tenant = "acme") })
            .authenticateAsToken(credentials)
        val session = checkNotNull(issued.captured.session)
        session.generation.assert().isEqualTo(1L)
        val family = checkNotNull(store.read(session.id)).family
        family.principal.value.assert().isEqualTo("alice")
        family.tenant.value.assert().isEqualTo("acme")
        issued.captured.notAfter.assert().isEqualTo(family.expiresAt)
        family.expiresAt.assert().isEqualTo(session.authTime.plus(families.lifetime))
    }

    private fun tokensBy(authentication: Authentication<Credentials>): TokenCompositeAuthentication {
        val authenticationProvider = DefaultAuthenticationProvider()
        authenticationProvider.register(credentials.javaClass, authentication)
        return TokenCompositeAuthentication(CompositeAuthentication(authenticationProvider), tokenConverter, families)
    }

    @Test
    fun authenticateAsToken() = runTest {
        val tokens = tokensBy(
            mockk {
                coEvery { authenticate(any()) } returns TokenSubject("alice", null)
            }
        )
        tokens.supportCredentials.assert().isEqualTo(Credentials::class.java)

        tokens.authenticateAsToken(credentials).assert().isEqualTo(compositeToken)
    }

    @Test
    fun `a failed authentication issues no token`() = runTest {
        val tokens = tokensBy(
            mockk {
                coEvery { authenticate(any()) } throws IllegalStateException("refused")
            }
        )

        assertThrows<IllegalStateException> { tokens.authenticateAsToken(credentials) }

        verify(exactly = 0) { tokenConverter.toToken(any()) }
    }

    /** Runs a login until [authenticate] answers or hangs, cancels it, and expects no token. */
    private fun assertCancelledLoginIssuesNoToken(authenticate: suspend (CompletableDeferred<Unit>) -> TokenSubject) =
        runTest {
            val started = CompletableDeferred<Unit>()
            val tokens = tokensBy(
                object : Authentication<Credentials> {
                    override val supportCredentials = Credentials::class.java

                    override suspend fun authenticate(credentials: Credentials): TokenSubject = authenticate(started)
                }
            )
            var issued = false
            val login = launch {
                tokens.authenticateAsToken(credentials)
                issued = true
            }
            started.await()
            login.cancelAndJoin()

            login.isCancelled.assert().isTrue()
            issued.assert().isFalse()
            verify(exactly = 0) { tokenConverter.toToken(any()) }
        }

    @Test
    fun `a cancelled authentication issues no token`() = assertCancelledLoginIssuesNoToken { started ->
        started.complete(Unit)
        awaitCancellation()
    }

    /** The authentication answers a subject, but its caller has gone: `ensureActive` before the token is issued. */
    @Test
    fun `a cancellation the authentication swallows still issues no token`() = assertCancelledLoginIssuesNoToken { started ->
        @Suppress("SwallowedException")
        try {
            started.complete(Unit)
            awaitCancellation()
        } catch (_: CancellationException) {
            TokenSubject("alice", null)
        }
    }
}
