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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.login

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.TokenSubject

class CompositeAuthentication @InternalCoSecApi constructor(
    private val authenticationProvider: AuthenticationProvider
) : Authentication<LoginCredentials> {
    override val credentialsType: Class<LoginCredentials>
        get() = LoginCredentials::class.java

    override suspend fun authenticate(credentials: LoginCredentials): TokenSubject {
        val credentialsType = credentials.javaClass
        return authenticate(credentialsType, credentials)
    }

    suspend fun authenticate(credentialsType: Class<out LoginCredentials>, credentials: LoginCredentials): TokenSubject {
        return authenticationProvider.getRequired<LoginCredentials, Authentication<LoginCredentials>>(
            credentialsType,
        ).authenticate(credentials)
    }
}
