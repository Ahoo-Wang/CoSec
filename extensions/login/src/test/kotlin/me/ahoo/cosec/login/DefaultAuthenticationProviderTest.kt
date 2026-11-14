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

package me.ahoo.cosec.login

import io.mockk.mockk
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.SimpleRefreshTokenAuthentication
import me.ahoo.cosec.runtime.token.TokenFamilies
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class DefaultAuthenticationProviderTest {
    private val authenticationProvider = DefaultAuthenticationProvider()

    @Test
    fun register() {
        assertThrows(IllegalArgumentException::class.java) {
            authenticationProvider.getRequired<RefreshTokenCredentials, SimpleRefreshTokenAuthentication>(
                RefreshTokenCredentials::class.java,
            )
        }
        val refreshTokenAuthentication = SimpleRefreshTokenAuthentication(
            mockk(),
            TokenRevocation(RevocationReader.NONE),
            TokenFamilies(InMemoryTokenFamilyStore()),
        )
        authenticationProvider.register(refreshTokenAuthentication)
        (
            authenticationProvider.getRequired<RefreshTokenCredentials, SimpleRefreshTokenAuthentication>(
                RefreshTokenCredentials::class.java,
            )
            ).assert().isEqualTo(refreshTokenAuthentication)

        (
            authenticationProvider.getRequired<RefreshTokenCredentials, SimpleRefreshTokenAuthentication>(
                MockRefreshTokenCredentials::class.java,
            )
            ).assert().isEqualTo(refreshTokenAuthentication)
    }

    /** #1222: registries share nothing. */
    @Test
    fun `a registration stays in its registry`() {
        authenticationProvider.register(
            SimpleRefreshTokenAuthentication(
                mockk(),
                TokenRevocation(RevocationReader.NONE),
                TokenFamilies(InMemoryTokenFamilyStore())
            )
        )

        DefaultAuthenticationProvider()
            .get<RefreshTokenCredentials, SimpleRefreshTokenAuthentication>(RefreshTokenCredentials::class.java)
            .assert().isNull()
    }

    data class MockRefreshTokenCredentials(override val accessToken: String, override val refreshToken: String) :
        RefreshTokenCredentials
}
