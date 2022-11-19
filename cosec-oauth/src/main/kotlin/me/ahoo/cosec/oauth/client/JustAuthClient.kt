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

import com.google.common.base.MoreObjects
import me.ahoo.cosec.oauth.OAuthException
import me.ahoo.cosec.oauth.OAuthUser
import me.ahoo.cosid.IdGenerator
import me.zhyd.oauth.enums.AuthUserGender
import me.zhyd.oauth.model.AuthResponse
import me.zhyd.oauth.model.AuthUser
import me.zhyd.oauth.request.AuthRequest
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * JustAuthClient .
 *
 * @author ahoo wang
 */
class JustAuthClient(
    override val name: String,
    private val authRequest: AuthRequest,
    private val idGenerator: IdGenerator
) : OAuthClient {
    override fun authorizeUrl(): String {
        return authRequest.authorize(idGenerator.generateAsString())
    }

    override fun authenticate(credentials: OAuthClientCredentials): Mono<OAuthUser> {
        return Mono.defer {
            @Suppress("UNCHECKED_CAST")
            val authResponse: AuthResponse<AuthUser> = authRequest.login(credentials) as AuthResponse<AuthUser>
            if (!authResponse.ok()) {
                throw OAuthException(MoreObjects.firstNonNull(authResponse.msg, authResponse.code.toString()))
            }
            val authUser = authResponse.data
            OAuthUser(
                id = authUser.uuid,
                username = authUser.username,
                nickname = authUser.nickname,
                avatar = authUser.avatar,
                email = authUser.email,
                location = authUser.location,
                gender = asGender(authUser.gender),
                rawInfo = authUser.rawUserInfo,
                provider = name
            ).toMono()
        }
    }

    private fun asGender(authUserGender: AuthUserGender): OAuthUser.Gender {
        return when (authUserGender) {
            AuthUserGender.MALE -> OAuthUser.Gender.MALE
            AuthUserGender.FEMALE -> OAuthUser.Gender.FEMALE
            AuthUserGender.UNKNOWN -> OAuthUser.Gender.UNKNOWN
            else -> throw IllegalStateException("Unexpected value: $authUserGender")
        }
    }

    fun name(): String {
        return name
    }
}
