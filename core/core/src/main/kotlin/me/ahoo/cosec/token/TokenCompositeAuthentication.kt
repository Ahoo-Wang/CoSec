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

package me.ahoo.cosec.token

import me.ahoo.cosec.api.authentication.Authentication
import me.ahoo.cosec.api.authentication.Credentials
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.authentication.CompositeAuthentication
import reactor.core.publisher.Mono

class TokenCompositeAuthentication(
    private val compositeAuthentication: CompositeAuthentication,
    private val tokenConverter: TokenConverter
) : Authentication<Credentials, CoSecPrincipal> {
    override val supportCredentials: Class<Credentials>
        get() = Credentials::class.java

    override fun authenticate(credentials: Credentials): Mono<out CoSecPrincipal> {
        return authenticate(credentials.javaClass, credentials)
    }

    fun authenticate(credentialsType: Class<out Credentials>, credentials: Credentials): Mono<out CoSecPrincipal> {
        return compositeAuthentication.authenticate(credentialsType, credentials)
    }

    fun authenticateAsToken(credentials: Credentials): Mono<out CompositeToken> {
        return authenticateAsToken(credentials.javaClass, credentials)
    }

    fun authenticateAsToken(
        credentialsType: Class<out Credentials>,
        credentials: Credentials
    ): Mono<out CompositeToken> {
        return authenticate(credentialsType, credentials)
            .map {
                tokenConverter.toToken(it)
            }
    }
}
