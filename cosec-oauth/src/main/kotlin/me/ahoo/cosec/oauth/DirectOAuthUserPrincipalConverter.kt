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
package me.ahoo.cosec.oauth

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * DirectOAuthUserPrincipalConverter .
 *
 * @author ahoo wang
 */
object DirectOAuthUserPrincipalConverter : OAuthUserPrincipalConverter {

    override fun convert(provider: String, authUser: OAuthUser): Mono<CoSecPrincipal> {
        authUser.rawInfo[OAuthUserPrincipalConverter.OAUTH_PROVIDER] = provider
        return SimplePrincipal(
            id = asProviderUserId(provider, authUser),
            policies = emptySet(),
            roles = emptySet(),
            attributes = authUser.rawInfo.mapValues { it.value.toString() },
        ).toMono()
    }

    /**
     * format: [OAuthUser.id]@[provider] as unique id.
     *
     * @param provider provider
     * @param authUser authUser
     * @return unique id
     */
    private fun asProviderUserId(provider: String, authUser: OAuthUser): String {
        return authUser.id + "@" + provider
    }
}
