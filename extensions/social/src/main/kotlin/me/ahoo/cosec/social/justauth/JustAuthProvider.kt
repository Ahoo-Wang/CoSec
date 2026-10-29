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

import com.google.common.base.MoreObjects
import me.ahoo.cosec.social.SocialAuthenticationException
import me.ahoo.cosec.social.SocialAuthenticationProvider
import me.ahoo.cosec.social.SocialCredentials
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.justauth.SocialUserConverter.toSocialUser
import me.zhyd.oauth.model.AuthResponse
import me.zhyd.oauth.model.AuthUser
import me.zhyd.oauth.request.AuthRequest
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.security.SecureRandom
import java.util.Base64

/**
 * A [SocialAuthenticationProvider] over a JustAuth [AuthRequest].
 *
 * @author ahoo wang
 */
class JustAuthProvider(
    override val name: String,
    private val authRequest: AuthRequest,
) : SocialAuthenticationProvider {
    private companion object {
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

    override fun authorizeUrl(): String {
        return authRequest.authorize(newState())
    }

    override fun authenticate(credentials: SocialCredentials): Mono<SocialUser> {
        require(credentials is JustAuthCredentials)
        return Mono.defer {
            @Suppress("UNCHECKED_CAST")
            val authResponse: AuthResponse<AuthUser> = authRequest.login(credentials) as AuthResponse<AuthUser>
            if (!authResponse.ok()) {
                throw SocialAuthenticationException(
                    MoreObjects.firstNonNull(authResponse.msg, authResponse.code.toString())
                )
            }
            val authUser = authResponse.data
            return@defer authUser.toSocialUser(name).toMono()
        }
    }

    fun name(): String {
        return name
    }
}
