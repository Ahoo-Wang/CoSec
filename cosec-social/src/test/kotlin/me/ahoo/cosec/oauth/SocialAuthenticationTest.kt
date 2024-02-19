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

package me.ahoo.cosec.oauth

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.social.justauth.JustAuthCredentials
import me.ahoo.cosec.social.SocialAuthentication
import me.ahoo.cosec.social.SocialCredentials
import me.ahoo.cosec.social.SocialProviderManager
import me.ahoo.cosec.social.SocialUser
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

internal class SocialAuthenticationTest {

    @Test
    fun authorizeUrl() {
        SocialProviderManager.register(
            "authorizeUrl",
            mockk {
                every { authorizeUrl() } returns "authorizeUrl"
            }
        )
        val authentication = SocialAuthentication()
        assertThat(authentication.supportCredentials, `is`(SocialCredentials::class.java))
        assertThat(authentication.authorizeUrl("authorizeUrl"), `is`("authorizeUrl"))
    }

    @Test
    fun authenticate() {
        SocialProviderManager.register(
            "authenticate",
            mockk {
                every { authorizeUrl() } returns "authenticate"
                every { authenticate(any()) } returns SocialUser(
                    id = "id",
                    username = "username",
                    provider = "authenticate"
                ).toMono()
            }
        )
        val authentication = SocialAuthentication()

        authentication.authenticate(JustAuthCredentials("authenticate"))
            .test()
            .consumeNextWith {
                assertThat(
                    it.id,
                    `is`("id@authenticate"),
                )
            }
            .verifyComplete()
    }
}
