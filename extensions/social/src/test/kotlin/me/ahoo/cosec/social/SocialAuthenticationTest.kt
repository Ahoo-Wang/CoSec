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

package me.ahoo.cosec.social

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.social.justauth.JustAuthCredentials
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SocialAuthenticationTest {
    private val providers = SocialProviderManager()
    private val authentication = SocialAuthentication(DirectSocialUserPrincipalConverter(null), providers)

    @Test
    fun authorize() = runTest {
        providers.register(
            "authorize",
            mockk {
                coEvery { authorize() } returns SocialAuthorization("authorizeUrl", "binding")
            }
        )
        authentication.supportCredentials.assert().isEqualTo(SocialCredentials::class.java)
        authentication.authorize("authorize").url.assert().isEqualTo("authorizeUrl")
        SocialAuthorization("u", "b").setCookie("/login/callback")
            .assert().isEqualTo(
                "cosec_oauth_binding=b; Path=/login/callback; Max-Age=180; HttpOnly; Secure; SameSite=Lax"
            )
    }

    @Test
    fun authenticate() = runTest {
        providers.register(
            "authenticate",
            mockk {
                coEvery { authenticate(any()) } returns SocialUser(
                    id = "id",
                    username = "username",
                    provider = "authenticate"
                )
            }
        )

        authentication.authenticate(
            JustAuthCredentials("authenticate", "binding")
        ).id.assert().isEqualTo("id@authenticate")
    }

    /** #1222: another manager's registration signs nobody in here. */
    @Test
    fun `a provider of another manager is unknown`() = runTest {
        SocialProviderManager().register("elsewhere", mockk())

        assertThrows<IllegalArgumentException> {
            authentication.authenticate(
                JustAuthCredentials("elsewhere", "binding")
            )
        }
    }
}
