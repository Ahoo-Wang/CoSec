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

package me.ahoo.cosec.runtime.authn

import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials

/**
 * The built-in authenticator (RFC 0006 §5.3.2): a request that carries an API key is judged by [apiKey] alone, as on
 * the 7.x path; any other request by [token]. A rejected key never falls back to the token, so presenting a bad key
 * cannot be retried with another credential in the same request.
 *
 * Without [apiKey], an API key is not read and the request goes to [token].
 */
class CompositeAuthenticator(
    private val token: Authenticator,
    private val apiKey: Authenticator? = null,
) : Authenticator {
    override suspend fun authenticate(credentials: Credentials): AuthnResult {
        if (apiKey != null && credentials.apiKey != null) {
            return apiKey.authenticate(credentials)
        }
        return token.authenticate(credentials)
    }
}
