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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.social.SocialAuthenticationException
import me.ahoo.cosec.social.SocialUser
import me.ahoo.test.asserts.assert
import me.zhyd.oauth.enums.AuthResponseStatus
import me.zhyd.oauth.enums.AuthUserGender
import me.zhyd.oauth.model.AuthCallback
import me.zhyd.oauth.model.AuthResponse
import me.zhyd.oauth.model.AuthToken
import me.zhyd.oauth.model.AuthUser
import me.zhyd.oauth.request.AuthRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class JustAuthProviderTest {
    /** A real blocking [AuthRequest] for the tests of threads and cancellation, which a mock's own locks would disturb. */
    private class BlockingAuthRequest(private val blockingCall: () -> Unit) : AuthRequest {
        override fun getAccessToken(authCallback: AuthCallback): AuthToken = AuthToken()

        override fun getUserInfo(authToken: AuthToken): AuthUser = AuthUser.builder().uuid("u1").build()

        override fun authorize(state: String): String {
            blockingCall()
            return "authorizeUrl"
        }

        override fun login(authCallback: AuthCallback): AuthResponse<AuthUser> {
            blockingCall()
            return AuthResponse.builder<AuthUser>()
                .code(AuthResponseStatus.SUCCESS.code)
                .data(getUserInfo(AuthToken()))
                .build()
        }
    }

    @Test
    fun authorizeUrl() = runTest {
        val authRequest = mockk<AuthRequest> {
            every { authorize(any()) } returns "authorizeUrl"
        }
        val authClient = JustAuthProvider("clientId", authRequest)
        authClient.name.assert().isEqualTo("clientId")
        authClient.authorizeUrl().assert().isEqualTo("authorizeUrl")
    }

    /** The state guards the callback against CSRF: 128 secure random bits each time, never an application id. */
    @Test
    fun `every state is 128 fresh random bits`() = runTest {
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

    /** RC1-14: JustAuth blocks, and the caller may be an event loop. */
    @Test
    fun `both blocking calls run off the caller's thread`(): Unit = runBlocking {
        val threads = CopyOnWriteArrayList<Thread>()
        val authClient = JustAuthProvider("clientId", BlockingAuthRequest { threads += Thread.currentThread() })

        authClient.authorizeUrl().assert().isEqualTo("authorizeUrl")
        authClient.authenticate(JustAuthCredentials("clientId")).id.assert().isEqualTo("u1")

        threads.assert().hasSize(2).doesNotContain(Thread.currentThread())
    }

    @Test
    fun `logins never run on more threads than the provider's bound`(): Unit = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val authClient = JustAuthProvider(
            "clientId",
            BlockingAuthRequest {
                peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                Thread.sleep(20)
                running.decrementAndGet()
            },
            parallelism = 2,
        )

        List(8) { async(Dispatchers.Default) { authClient.authenticate(JustAuthCredentials("clientId")) } }.awaitAll()

        peak.get().assert().isBetween(1, 2)
    }

    /** Runs a login that blocks in [whileBlocked], cancels it there, and expects no user, whatever the call does then. */
    private fun assertCancelledLoginAnswersNoUser(whileBlocked: () -> Unit) = runBlocking {
        val started = CountDownLatch(1)
        val authClient = JustAuthProvider(
            "clientId",
            BlockingAuthRequest {
                started.countDown()
                whileBlocked()
            },
        )
        val user = AtomicBoolean()
        val login = launch(Dispatchers.Default) {
            authClient.authenticate(JustAuthCredentials("clientId")).also { _: SocialUser -> user.set(true) }
        }
        runInterruptible(Dispatchers.IO) { started.await() }

        login.cancelAndJoin()

        login.isCancelled.assert().isTrue()
        user.get().assert().isFalse()
    }

    /** Cancellation reaches the blocking call, so a caller that has gone stops holding one of the provider's threads. */
    @Test
    fun `a cancelled login is interrupted and answers no user`() {
        val interrupted = AtomicBoolean()
        assertCancelledLoginAnswersNoUser {
            try {
                Thread.sleep(60_000)
            } catch (e: InterruptedException) {
                interrupted.set(true)
                throw e
            }
        }
        interrupted.get().assert().isTrue()
    }

    /** JustAuth catches what its HTTP client throws, so a late success must not reach a caller that has gone. */
    @Test
    fun `a login that ignores the interrupt answers no user`() {
        assertCancelledLoginAnswersNoUser {
            @Suppress("SwallowedException")
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                Unit
            }
        }
    }

    @Test
    fun authenticateFail() = runTest {
        val oAuthCredentials = JustAuthCredentials("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns false
                every { msg } returns "msg"
                every { code } returns -1
            }
        }
        val authClient = JustAuthProvider("clientId", authRequest)

        assertThrows<SocialAuthenticationException> { authClient.authenticate(oAuthCredentials) }
            .message.assert().isEqualTo("msg")
    }

    @Test
    fun authenticateFailWithoutMessage() = runTest {
        val oAuthCredentials = JustAuthCredentials("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns false
                every { msg } returns null
                every { code } returns -1
            }
        }

        assertThrows<SocialAuthenticationException> {
            JustAuthProvider("clientId", authRequest).authenticate(oAuthCredentials)
        }.message.assert().isEqualTo("-1")
    }

    @Test
    fun authenticateSuccess() = runTest {
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
        val user = authClient.authenticate(oAuthCredentials)
        user.id.assert().isEqualTo("uuid")
        user.username.assert().isEqualTo("username")
    }

    /** A success response whose user has none of the optional profile fields still logs in (#1249). */
    @Test
    fun authenticateSuccessWithoutOptionalFields() = runTest {
        val oAuthCredentials = JustAuthCredentials("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns true
                every { data } returns AuthUser.builder().uuid("u1").build()
            }
        }
        val user = JustAuthProvider("figma", authRequest).authenticate(oAuthCredentials)
        user.id.assert().isEqualTo("u1")
        user.provider.assert().isEqualTo("figma")
    }

    @Test
    fun authenticateSuccessWithoutUser() = runTest {
        val oAuthCredentials = JustAuthCredentials("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns true
                every { data } returns null
            }
        }
        assertThrows<SocialAuthenticationException> {
            JustAuthProvider("clientId", authRequest).authenticate(oAuthCredentials)
        }
    }
}
