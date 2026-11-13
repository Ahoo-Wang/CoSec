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

package me.ahoo.cosec.login.token

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.Credentials

class TokenCompositeAuthentication(
    private val compositeAuthentication: CompositeAuthentication,
    private val tokenConverter: TokenConverter
) : Authentication<Credentials> {
    override val supportCredentials: Class<Credentials>
        get() = Credentials::class.java

    override suspend fun authenticate(credentials: Credentials): TokenSubject {
        return authenticate(credentials.javaClass, credentials)
    }

    suspend fun authenticate(credentialsType: Class<out Credentials>, credentials: Credentials): TokenSubject {
        return compositeAuthentication.authenticate(credentialsType, credentials)
    }

    suspend fun authenticateAsToken(credentials: Credentials): CompositeToken {
        return authenticateAsToken(credentials.javaClass, credentials)
    }

    /**
     * Issues the token only for an authentication that succeeded and whose caller still waits: one that finished
     * without suspending, or that caught the cancellation, answers a subject for a caller that is gone.
     */
    suspend fun authenticateAsToken(
        credentialsType: Class<out Credentials>,
        credentials: Credentials
    ): CompositeToken {
        val subject = authenticate(credentialsType, credentials)
        currentCoroutineContext().ensureActive()
        return tokenConverter.toToken(subject)
    }
}
