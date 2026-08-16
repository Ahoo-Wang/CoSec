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
 * Implementations are consulted on every authenticated request, so lookups should
 * be cheap; distributed implementations are expected to trade a short propagation
 * window for locality (see `me.ahoo.cosec.cache.CoCacheTokenStore` in cosec-cocache).
 *
 * @see NoOp
 * @see RevocableTokenVerifier
 * @see TokenRevoker
 */
interface TokenStore {

    /**
     * Revokes a token by its id.
     *
     * @param tokenId the token id (jti) to revoke
     * @param ttl how long to keep the revocation entry; must cover the remaining
     * lifetime of the refresh token bound to [tokenId], otherwise that refresh
     * token could be revived once the entry expires
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
     *
     * This is the default wired by the Spring Boot starter, keeping verification
     * fully stateless: logout is inert until a real [TokenStore] bean is provided.
     */
    object NoOp : TokenStore {
        override fun revoke(tokenId: String, ttl: Duration) = Unit
        override fun isRevoked(tokenId: String): Boolean = false
    }
}
