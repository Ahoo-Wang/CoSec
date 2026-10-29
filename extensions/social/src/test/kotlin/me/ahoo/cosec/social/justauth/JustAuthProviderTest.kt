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

package me.ahoo.cosec.social.justauth

import com.alibaba.fastjson.JSONObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.cosec.social.SocialAuthenticationException
import me.ahoo.test.asserts.assert
import me.zhyd.oauth.enums.AuthUserGender
import me.zhyd.oauth.model.AuthResponse
import me.zhyd.oauth.model.AuthToken
import me.zhyd.oauth.model.AuthUser
import me.zhyd.oauth.request.AuthRequest
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.util.Base64

internal class JustAuthProviderTest {

    @Test
    fun authorizeUrl() {
        val authRequest = mockk<AuthRequest> {
            every { authorize(any()) } returns "authorizeUrl"
        }
        val authClient = JustAuthProvider("clientId", authRequest)
        authClient.name.assert().isEqualTo("clientId")
        authClient.authorizeUrl().assert().isEqualTo("authorizeUrl")
    }

    /** The state guards the callback against CSRF: 128 secure random bits each time, never an application id. */
    @Test
    fun `every state is 128 fresh random bits`() {
        val states = mutableListOf<String>()
        val state = slot<String>()
        val authRequest = mockk<AuthRequest> {
            every { authorize(capture(state)) } answers {
                states += state.captured
                "authorizeUrl"
            }
        }
        val authClient = JustAuthProvider("clientId", authRequest)
        repeat(1_000) { authClient.authorizeUrl() }
        states.toSet().assert().hasSize(1_000)
        states.map { Base64.getUrlDecoder().decode(it).size }.toSet().assert().containsExactly(16)
        states.filterNot { Regex("[A-Za-z0-9_-]{22}").matches(it) }.assert().isEmpty()
    }

    @Test
    fun authenticateFail() {
        val oAuthCredentials = JustAuthCredentials("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns false
                every { msg } returns "msg"
                every { code } returns -1
            }
        }
        val authClient = JustAuthProvider("clientId", authRequest)

        authClient.authenticate(oAuthCredentials)
            .test()
            .expectError(SocialAuthenticationException::class.java)
            .verify()
    }

    @Test
    fun authenticateSuccess() {
        val oAuthCredentials = JustAuthCredentials("clientId")
        val authUser = AuthUser()
        authUser.uuid = "uuid"
        authUser.username = "username"
        authUser.gender = AuthUserGender.MALE
        authUser.token = AuthToken()
        authUser.rawUserInfo = JSONObject()
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns true
                every { data } returns authUser
            }
        }
        val authClient = JustAuthProvider("clientId", authRequest)
        authClient.authenticate(oAuthCredentials)
            .test()
            .consumeNextWith {
                it.id.assert().isEqualTo("uuid")
                it.username.assert().isEqualTo("username")
            }
            .verifyComplete()
    }
}
