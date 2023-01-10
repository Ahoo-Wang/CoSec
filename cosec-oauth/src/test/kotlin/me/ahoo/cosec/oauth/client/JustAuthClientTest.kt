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

import com.alibaba.fastjson.JSONObject
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.oauth.OAuthException
import me.ahoo.cosid.test.MockIdGenerator
import me.zhyd.oauth.enums.AuthUserGender
import me.zhyd.oauth.model.AuthResponse
import me.zhyd.oauth.model.AuthToken
import me.zhyd.oauth.model.AuthUser
import me.zhyd.oauth.request.AuthRequest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class JustAuthClientTest {

    @Test
    fun authorizeUrl() {
        val authRequest = mockk<AuthRequest>() {
            every { authorize(any()) } returns "authorizeUrl"
        }
        val authClient = JustAuthClient("clientId", authRequest, MockIdGenerator.INSTANCE)
        assertThat(authClient.name, `is`("clientId"))
        assertThat(authClient.authorizeUrl(), `is`("authorizeUrl"))
    }

    @Test
    fun authenticateFail() {
        val oAuthClientCredentials = OAuthClientCredentials("clientId")
        val authRequest = mockk<AuthRequest>() {
            every { login(oAuthClientCredentials) } returns mockk<AuthResponse<AuthUser>>() {
                every { ok() } returns false
                every { msg } returns "msg"
                every { code } returns -1
            }
        }
        val authClient = JustAuthClient("clientId", authRequest, MockIdGenerator.INSTANCE)

        authClient.authenticate(oAuthClientCredentials)
            .test()
            .expectError(OAuthException::class.java)
            .verify()
    }

    @Test
    fun authenticateSuccess() {
        val oAuthClientCredentials = OAuthClientCredentials("clientId")
        val authRequest = mockk<AuthRequest>() {
            every { login(oAuthClientCredentials) } returns mockk<AuthResponse<AuthUser>>() {
                every { ok() } returns true
                every { data } returns
                    AuthUser(
                        "uuid",
                        "username",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        AuthUserGender.MALE,
                        "",
                        AuthToken(),
                        JSONObject(),
                    )
            }
        }
        val authClient = JustAuthClient("clientId", authRequest, MockIdGenerator.INSTANCE)
        authClient.authenticate(oAuthClientCredentials)
            .test()
            .consumeNextWith {
                assertThat(it.id, `is`("uuid"))
                assertThat(it.username, `is`("username"))
            }
            .verifyComplete()
    }
}
