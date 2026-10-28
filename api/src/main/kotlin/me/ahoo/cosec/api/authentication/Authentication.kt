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
import reactor.core.publisher.Mono

/**
 * Authentication interface for credential verification.
 *
 * Authentication is the process of verifying the identity of a user
 * by validating their credentials. If successful, it returns a
 * [CoSecPrincipal] representing the authenticated user.
 *
 * @param C The type of credentials this authentication supports
 * @param P The type of principal returned on successful authentication
 *
 * @see Credentials
 * @see AuthenticationProvider
 * @see CoSecPrincipal
 */
interface Authentication<C : Credentials, out P : CoSecPrincipal> {
    /**
     * The type of credentials this authentication supports.
     */
    val supportCredentials: Class<C>

    /**
     * Authenticates the provided credentials.
     *
     * @param credentials The credentials to validate
     * @return [Mono] emitting the authenticated principal, or empty if authentication fails
     */
    fun authenticate(credentials: C): Mono<out P>
}
