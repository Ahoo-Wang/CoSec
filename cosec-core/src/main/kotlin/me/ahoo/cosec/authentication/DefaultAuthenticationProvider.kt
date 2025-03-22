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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authentication.Authentication
import me.ahoo.cosec.api.authentication.AuthenticationProvider
import me.ahoo.cosec.api.authentication.Credentials
import me.ahoo.cosec.api.principal.CoSecPrincipal
import java.util.concurrent.ConcurrentHashMap

/**
 * Default Authentication Provider .
 *
 * @author ahoo wang
 */
object DefaultAuthenticationProvider : AuthenticationProvider {
    private val log = KotlinLogging.logger {}
    private val authenticationMaps:
        MutableMap<Class<out Credentials>, Authentication<out Credentials, CoSecPrincipal>> = ConcurrentHashMap()

    override fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> register(
        credentialsType: Class<C>,
        authentication: A
    ) {
        log.info {
            "Register Authentication: $authentication for Credentials: $credentialsType."
        }
        authenticationMaps[credentialsType] = authentication
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : Credentials, P : CoSecPrincipal, A : Authentication<C, P>> get(
        credentialsType: Class<out Credentials>
    ): A? {
        val authentication = authenticationMaps[credentialsType] as A?
        if (authentication != null) {
            return authentication
        }
        return authenticationMaps.values
            .firstOrNull {
                it.supportCredentials.isAssignableFrom(credentialsType)
            }?.let {
                return it as A
            }
    }
}
