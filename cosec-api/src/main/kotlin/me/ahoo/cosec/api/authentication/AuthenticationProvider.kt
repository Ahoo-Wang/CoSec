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
package me.ahoo.cosec.api.authentication

import me.ahoo.cosec.api.principal.CoSecPrincipal

/**
 * Provider for managing authentication implementations.
 *
 * This interface allows registration and retrieval of [Authentication]
 * implementations based on credential types. It supports multiple
 * authentication mechanisms (username/password, OAuth, JWT, etc.).
 *
 * @see Authentication
 * @see Credentials
 */
interface AuthenticationProvider {
    fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> register(
        credentialsType: Class<C>,
        authentication: A
    )

    fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> register(authentication: A) {
        register(authentication.supportCredentials, authentication)
    }

    operator fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> get(credentialsType: Class<out Credentials>): A?

    fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> getRequired(credentialsType: Class<out Credentials>): A =
        requireNotNull(get<C, P, A>(credentialsType)) {
            "Can not found Authentication by credentialsType:[${credentialsType.name}]"
        }
}
