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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.api.token.CompositeToken

/**
 * Verifier for validating access and refresh tokens.
 *
 * This interface is responsible for:
 * - Verifying the validity of access tokens
 * - Extracting principal information from tokens
 * - Refreshing tokens using refresh tokens
 *
 */
@CoSecSpi
interface TokenVerifier {
    /**
     * Verifies an access token and returns what it says.
     *
     * @param accessToken The access token to verify
     * @return What the token says
     * @throws TokenVerificationException if verification fails; [DelegationUnsupportedException] for a token that
     *   delegates (an `act` claim), which must never become a principal (`IDN-4`)
     */
    @Throws(TokenVerificationException::class)
    fun verify(accessToken: String): VerifiedToken

    /**
     * Refreshes tokens using a refresh token.
     *
     * @param token The composite token containing refresh token
     * @return What the access token of [token] says, for the new token to say again
     * @throws TokenVerificationException if refresh fails
     */
    @Throws(TokenVerificationException::class)
    fun refresh(token: CompositeToken): VerifiedToken
}
