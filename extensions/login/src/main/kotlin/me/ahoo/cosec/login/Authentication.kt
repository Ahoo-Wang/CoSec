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

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.login.token.TokenSubject

/**
 * Authentication interface for credential verification.
 *
 * Authentication is the process of verifying the identity of a user
 * by validating their credentials. If successful, it returns the
 * [TokenSubject] a token is issued for.
 *
 * It runs on the caller's thread, which may be an event loop: an implementation over a blocking client moves the call to
 * its own bounded dispatcher, with `runInterruptible(myDispatcher)`.
 *
 * @param C The type of credentials this authentication supports
 *
 * @see Credentials
 * @see AuthenticationProvider
 * @see TokenSubject
 */
@CoSecSpi
interface Authentication<C : Credentials> {
    /**
     * The type of credentials this authentication supports.
     */
    val supportCredentials: Class<C>

    /**
     * Authenticates the provided credentials.
     *
     * @param credentials The credentials to validate
     * @return What the token will say
     * @throws Exception when authentication fails: there is no value that means "refused", so a failure cannot be
     *   mistaken for a subject. Never catch a `CancellationException` here.
     */
    suspend fun authenticate(credentials: C): TokenSubject
}
