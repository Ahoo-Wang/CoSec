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

package me.ahoo.cosec.spring.boot.jwt

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.Lookup
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.cocache.CoCacheTokenStore
import me.ahoo.cosec.cocache.RevokedSubjectCache
import me.ahoo.cosec.cocache.RevokedTokenCache
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.authn.UncheckedRevocations
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * R2D-8 (`PIP-7`) on the starter's revocation reader: a Redis that hangs is answered within the reader's budget, well
 * inside the authenticator's 1 s, as a Redis that refuses is. The token is accepted, and counted, by default; with
 * `fail-closed` the check fails, which the pipeline answers 503.
 */
class RevocationReadBudgetTest {
    private object Verifier : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = VerifiedToken("jti-1", "alice", "acme", null)

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private val hanging = CoCacheTokenStore(
        mockk<RevokedTokenCache> { every { async().lookup(any()) } returns CompletableFuture() },
        mockk<RevokedSubjectCache> { every { async().lookup(any()) } returns CompletableFuture<Lookup<Long>>() },
        Duration.ofMillis(BUDGET_MILLIS),
    ).reader

    @Test
    fun `a hanging Redis accepts the token within the reader's budget`() {
        val unchecked = UncheckedRevocations()
        val started = System.nanoTime()
        val answer = runBlocking {
            TokenAuthenticator(Verifier, hanging, unchecked = unchecked).authenticate(Credentials("t", null))
        }
        val took = Duration.ofNanos(System.nanoTime() - started)
        (answer as AuthnResult.Authenticated).principal.id.assert().isEqualTo(PrincipalId("alice"))
        unchecked.count.assert().isEqualTo(1)
        took.assert().isLessThan(Duration.ofSeconds(1))
    }

    @Test
    fun `with fail-closed a hanging Redis fails the check within the reader's budget`() {
        val failure = runCatching {
            runBlocking {
                TokenAuthenticator(Verifier, hanging, acceptUnavailableRevocations = false)
                    .authenticate(Credentials("t", null))
            }
        }.exceptionOrNull()
        failure.assert().isInstanceOf(RevocationUnavailableException::class.java)
    }

    private companion object {
        const val BUDGET_MILLIS = 100L
    }
}
