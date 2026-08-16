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

import java.time.Duration

/**
 * Store for token revocation.
 *
 * Tokens are revoked by their [me.ahoo.cosec.api.token.TokenIdCapable.tokenId] (jti).
 * A revoked token is rejected by [RevocableTokenVerifier] until its natural expiry,
 * so entries only need to outlive the refresh token bound to them.
 *
 * @see NoOp
 * @see RevocableTokenVerifier
 */
interface TokenStore {

    /**
     * Revokes a token by its id.
     *
     * @param tokenId the token id (jti) to revoke
     * @param ttl how long the revocation entry must outlive, at least the bound refresh token
     */
    fun revoke(tokenId: String, ttl: Duration)

    /**
     * Checks whether the token id has been revoked.
     *
     * @param tokenId the token id (jti) to check
     * @return true if the token has been revoked
     */
    fun isRevoked(tokenId: String): Boolean

    /**
     * No-op token store that never revokes anything.
     * Use this to keep the stateless default behavior.
     */
    object NoOp : TokenStore {
        override fun revoke(tokenId: String, ttl: Duration) = Unit
        override fun isRevoked(tokenId: String): Boolean = false
    }
}
