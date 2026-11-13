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
package me.ahoo.cosec.social

import me.ahoo.cosec.login.token.TokenSubject

/**
 * The principal of a social login as it is: the id `<provider user id>@<provider>`, no roles, policies or tenant. Its
 * token is for [app], the app of the host it signs in to (RFC 0012 §3.5), or none.
 *
 * Its attributes become signed token claims, which conditions read as `subject.attributes.*` and a gateway may
 * forward, so they are only what the provider owns and the user cannot edit (RC1-25): the registration name
 * ([SocialUserPrincipalConverter.OAUTH_PROVIDER]) and the provider's id of the user
 * ([SocialUserPrincipalConverter.OAUTH_USER_ID]). The profile (name, nickname, email, company, bio, the raw user info)
 * is the user's to change, so none of it is signed; a converter of your own looks the user up instead.
 *
 * @author ahoo wang
 */
class DirectSocialUserPrincipalConverter(private val app: String?) : SocialUserPrincipalConverter {

    override suspend fun convert(provider: String, authUser: SocialUser): TokenSubject {
        return TokenSubject(
            id = asProviderUserId(provider, authUser),
            app = app,
            attributes = mapOf(
                SocialUserPrincipalConverter.OAUTH_PROVIDER to provider,
                SocialUserPrincipalConverter.OAUTH_USER_ID to authUser.id,
            ),
        )
    }

    /**
     * format: [SocialUser.id]@[provider] as unique id.
     *
     * @param provider provider
     * @param authUser authUser
     * @return unique id
     */
    private fun asProviderUserId(provider: String, authUser: SocialUser): String {
        return authUser.id + "@" + provider
    }
}
