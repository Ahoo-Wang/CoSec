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

import me.ahoo.cosec.social.SocialAuthenticationException
import me.ahoo.cosec.social.SocialUser
import me.zhyd.oauth.enums.AuthUserGender
import me.zhyd.oauth.model.AuthUser

internal object SocialUserConverter {
    fun AuthUserGender.toSocialUserGender(): SocialUser.Gender {
        return when (this) {
            AuthUserGender.MALE -> SocialUser.Gender.MALE
            AuthUserGender.FEMALE -> SocialUser.Gender.FEMALE
            AuthUserGender.UNKNOWN -> SocialUser.Gender.UNKNOWN
        }
    }

    /**
     * JustAuth's providers fill their [AuthUser] as far as the provider's API goes: Figma sets no gender, StackOverflow
     * no username, and some no raw user info. Only the provider's own id is the identity, so only that is required:
     * without it the sign-in is refused. A profile field that is missing reads as absent ([SocialUser.username] is
     * then that id, [SocialUser.Gender.UNKNOWN] the gender, and the raw info is empty).
     */
    fun AuthUser.toSocialUser(provider: String): SocialUser {
        val id: String? = this.uuid
        if (id.isNullOrBlank()) {
            throw SocialAuthenticationException("The [$provider] provider returned a user without an id.")
        }
        val gender: AuthUserGender? = this.gender
        val rawInfo: MutableMap<String, Any>? = this.rawUserInfo
        return SocialUser(
            id = id,
            username = this.username ?: id,
            nickname = this.nickname,
            avatar = this.avatar,
            email = this.email,
            location = this.location,
            gender = gender?.toSocialUserGender() ?: SocialUser.Gender.UNKNOWN,
            rawInfo = rawInfo ?: mutableMapOf(),
            provider = provider,
        )
    }
}
