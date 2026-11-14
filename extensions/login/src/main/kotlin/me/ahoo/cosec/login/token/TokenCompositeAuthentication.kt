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
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenFamilies

/**
 * Turns credentials into a token: the [Authentication] for their type answers the subject, and [tokenConverter] issues
 * its pair. A subject without a session (a login, a tenant switch) starts a family in [families], signed in at
 * [TokenSubject.authTime] or now; a refresh's subject is already in its session's next generation.
 */
class TokenCompositeAuthentication(
    private val compositeAuthentication: CompositeAuthentication,
    private val tokenConverter: TokenConverter,
    private val families: TokenFamilies,
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
        val issued = subject.session?.let { subject } ?: subject.inSession(
            families.start(
                subject.tenant?.let(::TenantId) ?: TenantId.DEFAULT,
                PrincipalId(subject.id),
                subject.authTime,
            )
        )
        return tokenConverter.toToken(issued)
    }
}
