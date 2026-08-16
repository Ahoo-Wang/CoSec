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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RevocableTokenVerifierTest {

    private val tokenVerifier = mockk<TokenVerifier>()
    private val tokenStore = mockk<TokenStore>()
    private val revocableTokenVerifier = RevocableTokenVerifier(tokenVerifier, tokenStore)

    private val tokenPrincipal = SimpleTokenPrincipal("tokenId", SimplePrincipal.ANONYMOUS)
    private val accessToken = SimpleAccessToken("accessToken")
    private val compositeToken = SimpleCompositeToken("accessToken", "refreshToken")

    @Test
    fun verifyWhenNotRevoked() {
        every { tokenVerifier.verify<TokenPrincipal>(accessToken) } returns tokenPrincipal
        every { tokenStore.isRevoked("tokenId") } returns false

        val actual = revocableTokenVerifier.verify<TokenPrincipal>(accessToken)

        actual.assert().isSameAs(tokenPrincipal)
    }

    @Test
    fun verifyWhenRevoked() {
        every { tokenVerifier.verify<TokenPrincipal>(accessToken) } returns tokenPrincipal
        every { tokenStore.isRevoked("tokenId") } returns true

        assertThrows<TokenRevokedException> {
            revocableTokenVerifier.verify<TokenPrincipal>(accessToken)
        }
    }

    @Test
    fun verifyWhenDelegateThrows() {
        every { tokenVerifier.verify<TokenPrincipal>(accessToken) } throws
            TokenVerificationException("Invalid token")

        assertThrows<TokenVerificationException> {
            revocableTokenVerifier.verify<TokenPrincipal>(accessToken)
        }
        verify(exactly = 0) { tokenStore.isRevoked(any()) }
    }

    @Test
    fun refreshWhenNotRevoked() {
        every { tokenVerifier.refresh<TokenPrincipal>(compositeToken) } returns tokenPrincipal
        every { tokenStore.isRevoked("tokenId") } returns false

        val actual = revocableTokenVerifier.refresh<TokenPrincipal>(compositeToken)

        actual.assert().isSameAs(tokenPrincipal)
    }

    @Test
    fun refreshWhenRevoked() {
        every { tokenVerifier.refresh<TokenPrincipal>(compositeToken) } returns tokenPrincipal
        every { tokenStore.isRevoked("tokenId") } returns true

        assertThrows<TokenRevokedException> {
            revocableTokenVerifier.refresh<TokenPrincipal>(compositeToken)
        }
    }
}
