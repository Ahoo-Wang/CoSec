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
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.DefaultAuthenticationProvider
import me.ahoo.cosec.login.LoginCredentials
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * Turns credentials into a token: the [Authentication] for their type answers the subject, and [tokenConverter] issues
 * its pair. A subject without a session (a login, a tenant switch) starts a family in [families], signed in at
 * [TokenSubject.authTime] or now; a refresh's subject is already in its session's next generation.
 *
 * Build one with [of], from the authentications of your logins; the starter makes one from its `Authentication` beans.
 */
class TokenCompositeAuthentication @InternalCoSecApi constructor(
    private val compositeAuthentication: CompositeAuthentication,
    private val tokenConverter: TokenConverter,
    private val families: TokenFamilies,
) : Authentication<LoginCredentials> {
    override val credentialsType: Class<LoginCredentials>
        get() = LoginCredentials::class.java

    override suspend fun authenticate(credentials: LoginCredentials): TokenSubject {
        return authenticate(credentials.javaClass, credentials)
    }

    suspend fun authenticate(credentialsType: Class<out LoginCredentials>, credentials: LoginCredentials): TokenSubject {
        return compositeAuthentication.authenticate(credentialsType, credentials)
    }

    suspend fun authenticateAsToken(credentials: LoginCredentials): CompositeToken {
        return authenticateAsToken(credentials.javaClass, credentials)
    }

    /**
     * Issues the token only for an authentication that succeeded and whose caller still waits: one that finished
     * without suspending, or that caught the cancellation, answers a subject for a caller that is gone.
     */
    suspend fun authenticateAsToken(
        credentialsType: Class<out LoginCredentials>,
        credentials: LoginCredentials
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

    companion object {
        /**
         * A login over [authentications], each found by the credentials type it supports (a type it is assignable to
         * when none is registered for the exact one; two candidates fail rather than pick by order), issuing its pair
         * with [tokenConverter] in a session of [families].
         */
        @OptIn(InternalCoSecApi::class)
        fun of(
            authentications: List<Authentication<*>>,
            tokenConverter: TokenConverter,
            families: TokenFamilies,
        ): TokenCompositeAuthentication {
            val provider = DefaultAuthenticationProvider()
            authentications.forEach {
                @Suppress("UNCHECKED_CAST")
                provider.register(it as Authentication<LoginCredentials>)
            }
            return TokenCompositeAuthentication(CompositeAuthentication(provider), tokenConverter, families)
        }
    }
}
