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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.ahoo.cosec.social.SocialAuthenticationException
import me.ahoo.cosec.social.SocialAuthenticationProvider
import me.ahoo.cosec.social.SocialAuthorization
import me.ahoo.cosec.social.SocialCredentials
import me.ahoo.cosec.social.SocialStateStore
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.justauth.SocialUserConverter.toSocialUser
import me.zhyd.oauth.model.AuthResponse
import me.zhyd.oauth.model.AuthUser
import me.zhyd.oauth.request.AuthRequest
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * A [SocialAuthenticationProvider] over a JustAuth [AuthRequest].
 *
 * The OAuth `state` keeps a forged callback out (CSRF), so CoSec checks it itself, before JustAuth sees the callback
 * (round-2 review M4): [authorize] draws a state and a binding from a [SecureRandom] and records the state in [states]
 * with the SHA-256 of the binding, which the application sets as a cookie on the browser ([SocialAuthorization]). A
 * callback is accepted only when its state is recorded, which [SocialStateStore.take] removes at once, so it is used at
 * most once, and the browser's binding hashes to the one recorded, compared in constant time. JustAuth's own check of
 * the state then still runs.
 *
 * JustAuth blocks: the authorize step writes the state, and a login calls the provider over blocking HTTP. Both run on
 * this provider's own dispatcher, of at most [parallelism] threads, so they never hold an event loop and a provider that
 * hangs delays only its own logins.
 *
 * @author ahoo wang
 */
class JustAuthProvider(
    override val name: String,
    private val authRequest: AuthRequest,
    private val states: SocialStateStore,
    parallelism: Int = DEFAULT_PARALLELISM,
) : SocialAuthenticationProvider {
    private val blocking = Dispatchers.IO.limitedParallelism(parallelism)

    private companion object {
        const val DEFAULT_PARALLELISM = 16
        const val RANDOM_BYTES = 16
        val STATE = Regex("[A-Za-z0-9_-]{22}")
        val lifetime: Duration = Duration.ofSeconds(SocialAuthorization.STATE_LIFETIME_SECONDS)
        val random = SecureRandom()
        val encoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * 128 bits from a [SecureRandom], base64url. A state and a binding must be unguessable: that is why they never come
         * from the application's `IdGenerator`, whose ids need not be, and why they are not pluggable.
         */
        fun fresh(): String = encoder.encodeToString(ByteArray(RANDOM_BYTES).also(random::nextBytes))

        fun hash(binding: String): String =
            encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(binding.toByteArray(Charsets.UTF_8)))
    }

    override suspend fun authorize(): SocialAuthorization = runInterruptible(blocking) {
        val state = fresh()
        val binding = fresh()
        states.put(state, hash(binding), lifetime)
        SocialAuthorization(authRequest.authorize(state), binding)
    }

    override suspend fun authenticate(credentials: SocialCredentials): SocialUser {
        require(credentials is JustAuthCredentials)
        return runInterruptible(blocking) {
            checkState(credentials)
            login(credentials)
        }.toSocialUser(name)
    }

    /** The callback's state is one [authorize] issued to the browser that sends [SocialCredentials.binding]. */
    private fun checkState(credentials: JustAuthCredentials) {
        refusal(credentials)?.let { throw SocialAuthenticationException(it) }
    }

    /** Why the callback is refused, or null when its state holds. */
    private fun refusal(credentials: JustAuthCredentials): String? {
        val state = credentials.state.orEmpty()
        if (!STATE.matches(state)) {
            return "The [$name] callback carries no state CoSec issued."
        }
        // Taken first, whatever follows: a state is never tried twice.
        val recorded = states.take(state) ?: return "The [$name] callback's state is unknown, expired or already used."
        val presented = hash(credentials.binding)
        return if (MessageDigest.isEqual(presented.toByteArray(), recorded.toByteArray())) {
            null
        } else {
            "The [$name] callback's state was issued to another browser."
        }
    }

    private fun login(credentials: JustAuthCredentials): AuthUser {
        val authResponse: AuthResponse<AuthUser> = authRequest.login(credentials)
        if (!authResponse.ok()) {
            throw SocialAuthenticationException(authResponse.msg ?: authResponse.code.toString())
        }
        return authResponse.data ?: throw SocialAuthenticationException("The [$name] provider returned no user.")
    }

    fun name(): String {
        return name
    }
}
