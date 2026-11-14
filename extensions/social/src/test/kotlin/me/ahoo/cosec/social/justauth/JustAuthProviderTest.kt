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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.social.InMemorySocialStateStore
import me.ahoo.cosec.social.SocialAuthenticationException
import me.ahoo.cosec.social.SocialStateStore
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class JustAuthProviderTest {
    private val states = InMemorySocialStateStore()

    /** The callback of a sign-in started for this browser: a state [states] holds, and the browser's binding. */
    private suspend fun issued(
        provider: String,
        binding: String = "binding-${System.nanoTime()}"
    ): JustAuthCredentials {
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(16).also(SecureRandom()::nextBytes)
        )
        states.put(state, sha256(binding), Duration.ofMinutes(3))
        return JustAuthCredentials(provider, binding).apply { this.state = state }
    }

    private fun sha256(text: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    /** The states the last [succeeding] request was authorized with, in order. */
    private val authorized = mutableListOf<String>()

    private fun succeeding(): AuthRequest = mockk {
        every { authorize(any()) } answers {
            authorized += firstArg<String>()
            "authorizeUrl"
        }
        every { login(any()) } returns mockk<AuthResponse<AuthUser>> {
            every { ok() } returns true
            every { data } returns AuthUser.builder().uuid("u1").build()
        }
    }

    /** M4: the authorize step's state signs in once: a second callback holding it is refused before JustAuth sees it. */
    @Test
    fun `a state is used once`() = runTest {
        val authRequest = succeeding()
        val provider = JustAuthProvider("github", authRequest, states)
        val started = provider.authorize()
        val callback = callback(authorized.single(), started.binding)

        provider.authenticate(callback).id.assert().isEqualTo("u1")
        assertThrows<SocialAuthenticationException> { provider.authenticate(callback) }
            .message.assert().contains("already used")
        verify(exactly = 1) { authRequest.login(any()) }
    }

    /**
     * M4: a state is bound to the browser that started the sign-in. An attacker's callback URL, opened in a victim's
     * browser, carries the attacker's state but the victim's binding (or none): refused, and the state is spent.
     */
    @Test
    fun `another browser's state is refused`() = runTest {
        val authRequest = succeeding()
        val provider = JustAuthProvider("github", authRequest, states)
        val attackers = provider.authorize()
        val victims = provider.authorize()

        val (attackersState, victimsState) = authorized
        assertThrows<SocialAuthenticationException> { provider.authenticate(callback(attackersState, victims.binding)) }
            .message.assert().contains("another browser")
        assertThrows<SocialAuthenticationException> { provider.authenticate(callback(victimsState, "")) }
        assertThrows<SocialAuthenticationException> {
            provider.authenticate(JustAuthCredentials("github", attackers.binding).apply { state = "made-up" })
        }.message.assert().contains("no state")
        verify(exactly = 0) { authRequest.login(any()) }
    }

    /** The provider's callback for [state], from a browser whose binding cookie is [binding]. */
    private fun callback(state: String, binding: String): JustAuthCredentials =
        JustAuthCredentials("github", binding).apply {
            this.state = state
            code = "code"
        }

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
        val authClient = JustAuthProvider("clientId", authRequest, states)
        authClient.name.assert().isEqualTo("clientId")
        authClient.authorize().url.assert().isEqualTo("authorizeUrl")
    }

    /** The state guards the callback against CSRF: 128 secure random bits each time, never an application id. */
    @Test
    fun `every state is 128 fresh random bits`() = runTest {
        val issued = mutableListOf<String>()
        val state = slot<String>()
        val authRequest = mockk<AuthRequest> {
            every { authorize(capture(state)) } answers {
                issued += state.captured
                "authorizeUrl"
            }
        }
        val authClient = JustAuthProvider("clientId", authRequest, states)
        val bindings = List(1_000) { authClient.authorize().binding }
        for (values in listOf(issued, bindings)) {
            values.toSet().assert().hasSize(1_000)
            values.map { Base64.getUrlDecoder().decode(it).size }.toSet().assert().containsExactly(16)
            values.filterNot { Regex("[A-Za-z0-9_-]{22}").matches(it) }.assert().isEmpty()
        }
        issued.intersect(bindings.toSet()).assert().isEmpty()
    }

    /** RC1-14: JustAuth blocks, and the caller may be an event loop. */
    @Test
    fun `both blocking calls run off the caller's thread`(): Unit = runBlocking {
        val threads = CopyOnWriteArrayList<Thread>()
        val authClient = JustAuthProvider("clientId", BlockingAuthRequest { threads += Thread.currentThread() }, states)

        authClient.authorize().url.assert().isEqualTo("authorizeUrl")
        authClient.authenticate(issued("clientId")).id.assert().isEqualTo("u1")

        threads.assert().hasSize(2).doesNotContain(Thread.currentThread())
    }

    /** The state store suspends: the provider calls it on the caller's dispatcher, outside its blocking section. */
    @Test
    fun `the state store is called outside the blocking section`(): Unit = runBlocking {
        val calls = CopyOnWriteArrayList<Thread>()
        var issuedState = ""
        val recording = object : SocialStateStore {
            override suspend fun put(state: String, bindingHash: String, lifetime: Duration) {
                calls += Thread.currentThread()
                issuedState = state
                states.put(state, bindingHash, lifetime)
            }

            override suspend fun take(state: String): String? {
                calls += Thread.currentThread()
                return states.take(state)
            }
        }
        val blockingCalls = CopyOnWriteArrayList<Thread>()
        val provider =
            JustAuthProvider("github", BlockingAuthRequest { blockingCalls += Thread.currentThread() }, recording)

        val started = provider.authorize()
        provider.authenticate(callback(issuedState, started.binding)).id.assert().isEqualTo("u1")

        calls.assert().hasSize(2).containsOnly(Thread.currentThread())
        blockingCalls.assert().hasSize(2).doesNotContain(Thread.currentThread())
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
            states,
            parallelism = 2,
        )

        List(8) { async(Dispatchers.Default) { authClient.authenticate(issued("clientId")) } }.awaitAll()

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
            states,
        )
        val user = AtomicBoolean()
        val login = launch(Dispatchers.Default) {
            authClient.authenticate(issued("clientId")).also { _: SocialUser -> user.set(true) }
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
        val oAuthCredentials = issued("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns false
                every { msg } returns "msg"
                every { code } returns -1
            }
        }
        val authClient = JustAuthProvider("clientId", authRequest, states)

        assertThrows<SocialAuthenticationException> { authClient.authenticate(oAuthCredentials) }
            .message.assert().isEqualTo("msg")
    }

    @Test
    fun authenticateFailWithoutMessage() = runTest {
        val oAuthCredentials = issued("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns false
                every { msg } returns null
                every { code } returns -1
            }
        }

        assertThrows<SocialAuthenticationException> {
            JustAuthProvider("clientId", authRequest, states).authenticate(oAuthCredentials)
        }.message.assert().isEqualTo("-1")
    }

    @Test
    fun authenticateSuccess() = runTest {
        val oAuthCredentials = issued("clientId")
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
        val authClient = JustAuthProvider("clientId", authRequest, states)
        val user = authClient.authenticate(oAuthCredentials)
        user.id.assert().isEqualTo("uuid")
        user.username.assert().isEqualTo("username")
    }

    /** A success response whose user has none of the optional profile fields still logs in (#1249). */
    @Test
    fun authenticateSuccessWithoutOptionalFields() = runTest {
        val oAuthCredentials = issued("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns true
                every { data } returns AuthUser.builder().uuid("u1").build()
            }
        }
        val user = JustAuthProvider("figma", authRequest, states).authenticate(oAuthCredentials)
        user.id.assert().isEqualTo("u1")
        user.provider.assert().isEqualTo("figma")
    }

    @Test
    fun authenticateSuccessWithoutUser() = runTest {
        val oAuthCredentials = issued("clientId")
        val authRequest = mockk<AuthRequest> {
            every { login(oAuthCredentials) } returns mockk<AuthResponse<AuthUser>> {
                every { ok() } returns true
                every { data } returns null
            }
        }
        assertThrows<SocialAuthenticationException> {
            JustAuthProvider("clientId", authRequest, states).authenticate(oAuthCredentials)
        }
    }
}
