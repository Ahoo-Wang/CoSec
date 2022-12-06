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

package me.ahoo.cosec.authentication

import me.ahoo.cosec.api.authentication.Authentication
import me.ahoo.cosec.api.authentication.AuthenticationProvider
import me.ahoo.cosec.api.authentication.Credentials
import me.ahoo.cosec.api.principal.CoSecPrincipal
import reactor.core.publisher.Mono

class CompositeAuthentication(
    private val authenticationProvider: AuthenticationProvider
) : Authentication<Credentials, CoSecPrincipal> {
    override val supportCredentials: Class<Credentials>
        get() = Credentials::class.java

    override fun authenticate(credentials: Credentials): Mono<CoSecPrincipal> {
        val credentialsType = credentials.javaClass
        return authenticate(credentialsType, credentials)
    }

    fun authenticate(credentialsType: Class<out Credentials>, credentials: Credentials): Mono<CoSecPrincipal> {
        return authenticationProvider.getRequired<Credentials, CoSecPrincipal, Authentication<Credentials, CoSecPrincipal>>(
            credentialsType
        ).authenticate(credentials)
    }
}
