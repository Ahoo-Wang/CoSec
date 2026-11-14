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
package me.ahoo.cosec.social

/**
 * Social Provider .
 *
 * Both calls may run on an event loop, so an implementation over a blocking client moves the work to its own bounded
 * dispatcher, as `JustAuthProvider` does.
 *
 * @author ahoo wang
 */
interface SocialAuthenticationProvider {
    /** The provider's registration name: the `provider` of a [SocialCredentials]. */
    val name: String

    /**
     * Starts a sign-in: a fresh, unguessable state, recorded single use with the SHA-256 of a fresh binding.
     *
     * @return the provider's authorize URL, and the binding the browser must send back ([SocialAuthorization])
     */
    suspend fun authorize(): SocialAuthorization

    /**
     * Finishes a sign-in. The callback's state must be one [authorize] issued, not yet used and not expired, and its
     * [SocialCredentials.binding] the browser's binding for it; the state is used up either way.
     *
     * @throws SocialAuthenticationException when the state or the binding does not hold, or the provider refuses
     */
    @Throws(SocialAuthenticationException::class)
    suspend fun authenticate(credentials: SocialCredentials): SocialUser
}
