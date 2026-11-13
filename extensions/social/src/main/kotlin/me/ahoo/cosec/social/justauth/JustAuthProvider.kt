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
import me.ahoo.cosec.social.SocialCredentials
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.justauth.SocialUserConverter.toSocialUser
import me.zhyd.oauth.model.AuthResponse
import me.zhyd.oauth.model.AuthUser
import me.zhyd.oauth.request.AuthRequest
import java.security.SecureRandom
import java.util.Base64

/**
 * A [SocialAuthenticationProvider] over a JustAuth [AuthRequest].
 *
 * JustAuth blocks: the authorize step draws the `state` from a [SecureRandom] and writes it to the state cache, and a
 * login calls the provider over blocking HTTP. Both run on this provider's own dispatcher, of at most [parallelism]
 * threads, so they never hold an event loop and a provider that hangs delays only its own logins.
 *
 * @author ahoo wang
 */
class JustAuthProvider(
    override val name: String,
    private val authRequest: AuthRequest,
    parallelism: Int = DEFAULT_PARALLELISM,
) : SocialAuthenticationProvider {
    private val blocking = Dispatchers.IO.limitedParallelism(parallelism)

    private companion object {
        const val DEFAULT_PARALLELISM = 16
        const val STATE_BYTES = 16
        val random = SecureRandom()
        val encoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * A fresh OAuth `state`: 128 bits from a [SecureRandom], base64url. The state is what keeps a forged callback
         * out (CSRF), so it must be unguessable. That is why it never comes from the application's `IdGenerator`,
         * whose ids need not be, and why it is not pluggable. Only the authorize step pays for the secure source.
         */
        fun newState(): String = encoder.encodeToString(ByteArray(STATE_BYTES).also(random::nextBytes))
    }

    override suspend fun authorizeUrl(): String = runInterruptible(blocking) { authRequest.authorize(newState()) }

    override suspend fun authenticate(credentials: SocialCredentials): SocialUser {
        require(credentials is JustAuthCredentials)
        return runInterruptible(blocking) { login(credentials) }.toSocialUser(name)
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
