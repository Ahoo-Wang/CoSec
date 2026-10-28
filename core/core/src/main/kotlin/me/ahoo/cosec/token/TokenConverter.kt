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
import me.ahoo.cosec.api.token.CompositeToken
import java.time.Duration

/**
 * Converter from principal to token.
 *
 * This interface is used to generate tokens (access and refresh)
 * for authenticated principals.
 *
 * @see TokenVerifier
 */
interface TokenConverter {
    /**
     * Converts a principal to a token with default validity periods.
     *
     * @param principal The principal to convert
     * @return The generated composite token
     */
    fun toToken(principal: CoSecPrincipal): CompositeToken

    /**
     * Converts a principal to a token with custom validity periods.
     *
     * @param principal The principal to convert
     * @param accessTokenValidity Duration for which access token is valid
     * @param refreshTokenValidity Duration for which refresh token is valid
     * @return The generated composite token
     */
    fun toToken(
        principal: CoSecPrincipal,
        accessTokenValidity: Duration,
        refreshTokenValidity: Duration
    ): CompositeToken
}
