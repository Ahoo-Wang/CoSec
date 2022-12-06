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
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple Authentication Provider .
 *
 * @author ahoo wang
 */
object DefaultAuthenticationProvider : AuthenticationProvider {
    private val log = LoggerFactory.getLogger(DefaultAuthenticationProvider::class.java)
    private val authenticationMaps: MutableMap<Class<out Credentials>, Authentication<out Credentials, out CoSecPrincipal>>

    init {
        authenticationMaps = ConcurrentHashMap()
    }

    override fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> register(
        credentialsType: Class<C>,
        authentication: A
    ) {
        if (log.isInfoEnabled) {
            log.info("Register Authentication: {} for Credentials: {}", authentication, credentialsType)
        }
        authenticationMaps[credentialsType] = authentication
    }

    override fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> get(
        credentialsType: Class<out Credentials>
    ): A? {
        @Suppress("UNCHECKED_CAST")
        return authenticationMaps[credentialsType] as A?
    }
}
