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

package me.ahoo.cosec.token

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal

/**
 * Verifier for validating access and refresh tokens.
 *
 * This interface is responsible for:
 * - Verifying the validity of access tokens
 * - Extracting principal information from tokens
 * - Refreshing tokens using refresh tokens
 *
 * @see PrincipalConverter
 * @see TokenConverter
 */
interface TokenVerifier : PrincipalConverter {
    /**
     * Verifies an access token and returns the principal.
     *
     * @param T The type of token principal
     * @param accessToken The access token to verify
     * @return The verified token principal
     * @throws TokenVerificationException if verification fails
     */
    @Throws(TokenVerificationException::class)
    fun <T : TokenPrincipal> verify(accessToken: AccessToken): T

    /**
     * Default implementation converting to principal via verify.
     */
    override fun toPrincipal(accessToken: AccessToken): CoSecPrincipal = verify(accessToken)

    /**
     * Refreshes tokens using a refresh token.
     *
     * @param T The type of token principal
     * @param token The composite token containing refresh token
     * @return New token principal with new tokens
     * @throws TokenVerificationException if refresh fails
     */
    @Throws(TokenVerificationException::class)
    fun <T : TokenPrincipal> refresh(token: CompositeToken): T
}
