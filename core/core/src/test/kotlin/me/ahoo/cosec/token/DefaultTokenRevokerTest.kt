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

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class DefaultTokenRevokerTest {

    private val tokenVerifier = mockk<TokenVerifier>()
    private val tokenStore = mockk<TokenStore>()
    private val revocationTtl = Duration.ofDays(7)
    private val tokenRevoker = DefaultTokenRevoker(tokenVerifier, tokenStore, revocationTtl)

    @Test
    fun revoke() {
        val tokenPrincipal = SimpleTokenPrincipal("tokenId", SimplePrincipal.ANONYMOUS)
        val accessToken = SimpleAccessToken("accessToken")
        every { tokenVerifier.verify<TokenPrincipal>(accessToken) } returns tokenPrincipal
        every { tokenStore.revoke("tokenId", revocationTtl) } just Runs

        val actual = tokenRevoker.revoke(accessToken)

        actual.assert().isSameAs(tokenPrincipal)
        verify(exactly = 1) { tokenStore.revoke("tokenId", revocationTtl) }
    }

    @Test
    fun revokeWhenTokenInvalid() {
        val accessToken = SimpleAccessToken("invalid")
        every { tokenVerifier.verify<TokenPrincipal>(accessToken) } throws
            TokenVerificationException("Invalid token")

        assertThrows<TokenVerificationException> {
            tokenRevoker.revoke(accessToken)
        }
        verify(exactly = 0) { tokenStore.revoke(any(), any()) }
    }
}
