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

import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.token.TokenSubject

/**
 * Social Authentication .
 *
 * @author ahoo wang
 */
class SocialAuthentication(
    private val principalConverter: SocialUserPrincipalConverter,
    private val providers: SocialProviderManager,
) : Authentication<SocialCredentials> {
    override val supportCredentials: Class<SocialCredentials>
        get() {
            return SocialCredentials::class.java
        }

    /**
     * 生成授权地址.
     *
     * @param provider OAuth Provider id
     * @return Authorize Url
     */
    suspend fun authorizeUrl(provider: String): String {
        return providers.getRequired(provider).authorizeUrl()
    }

    override suspend fun authenticate(credentials: SocialCredentials): TokenSubject {
        val user = providers.getRequired(credentials.provider).authenticate(credentials)
        return principalConverter.convert(credentials.provider, user)
    }
}
