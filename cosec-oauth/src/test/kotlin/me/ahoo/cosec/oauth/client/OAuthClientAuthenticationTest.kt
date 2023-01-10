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

package me.ahoo.cosec.oauth.client

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.oauth.OAuthUser
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

internal class OAuthClientAuthenticationTest {

    @Test
    fun authorizeUrl() {
        val clientManager = mockk<OAuthClientManager> {
            every { getRequired(any()).authorizeUrl() } returns "authorizeUrl"
        }
        val authentication = OAuthClientAuthentication(clientManager)
        assertThat(authentication.supportCredentials, `is`(OAuthClientCredentials::class.java))
        assertThat(authentication.authorizeUrl(""), `is`("authorizeUrl"))
    }

    @Test
    fun authenticate() {
        val clientManager = mockk<OAuthClientManager> {
            every {
                getRequired(any())
                    .authenticate(any())
            } returns OAuthUser(id = "id", username = "username", provider = "provider").toMono()
        }
        val authentication = OAuthClientAuthentication(clientManager)

        authentication.authenticate(OAuthClientCredentials("clientId"))
            .test()
            .consumeNextWith {
                assertThat(
                    it.id,
                    `is`("id@clientId"),
                )
            }
            .verifyComplete()
    }
}
