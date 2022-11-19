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

import me.ahoo.cosec.oauth.OAuthUser
import me.ahoo.cosec.principal.CoSecPrincipal
import me.ahoo.cosec.util.Internals.format
import reactor.core.publisher.Mono

/**
 * OAuth Client Principal Converter .
 *
 * @author ahoo wang
 */

fun interface OAuthClientPrincipalConverter {
    fun convert(client: String, authUser: OAuthUser): Mono<CoSecPrincipal>

    companion object {
        @JvmField
        val OAUTH_CLIENT = format("oauth_client")
    }
}
