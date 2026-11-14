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
package me.ahoo.cosec.login

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * The registry of [Authentication]s. One instance per application: the starter assembles one for each Spring context,
 * so what one context registers or closes never reaches another (#1222).
 *
 * A credentials type finds the [Authentication] registered for it, else the one whose [Authentication.supportCredentials]
 * it is assignable to. When more than one is, [get] fails rather than pick by registration order: which authentication
 * checks a login must never depend on it.
 *
 * @author ahoo wang
 */
class DefaultAuthenticationProvider : AuthenticationProvider {
    private val authenticationMaps:
        MutableMap<Class<out Credentials>, Authentication<out Credentials>> = ConcurrentHashMap()

    override fun <C : Credentials, A : Authentication<C>> register(
        credentialsType: Class<C>,
        authentication: A
    ) {
        log.info {
            "Register Authentication: $authentication for Credentials: $credentialsType."
        }
        authenticationMaps[credentialsType] = authentication
    }

    @Suppress("UNCHECKED_CAST")
    override fun <C : Credentials, A : Authentication<C>> get(
        credentialsType: Class<out Credentials>
    ): A? {
        val authentication = authenticationMaps[credentialsType] as A?
        if (authentication != null) {
            return authentication
        }
        // Which one supports a type registered for none of them is not a matter of order (round-2 review L3).
        val supporting = authenticationMaps.values.filter { it.supportCredentials.isAssignableFrom(credentialsType) }
        check(supporting.size <= 1) {
            "Credentials [${credentialsType.name}] are supported by more than one Authentication " +
                "(${supporting.joinToString { it.javaClass.name }}): register the one for this type."
        }
        return supporting.singleOrNull() as A?
    }
}
