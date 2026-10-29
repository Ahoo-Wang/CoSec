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
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import java.time.Duration
import java.time.Instant

/**
 * Store for token revocation.
 *
 * Tokens are revoked by their [me.ahoo.cosec.api.token.TokenIdCapable.tokenId] (jti),
 * or all at once by their principal ([revokeSubject]).
 * A revoked token is rejected by `RevocableTokenVerifier` until its natural expiry,
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
@CoSecSpi
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
     * Revokes every token of [principal] in [tenant] issued before [issuedBefore] (RFC 0004 §4.5.3).
     *
     * A later call with an earlier [issuedBefore] must not shorten an existing entry. Stores that cannot record it
     * throw, so that an emergency revocation never silently does nothing.
     *
     * @param ttl how long to keep the entry; must cover the lifetime of the refresh tokens issued until [issuedBefore]
     * @throws UnsupportedOperationException if the store does not support it, as the default does
     */
    fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration): Unit =
        throw UnsupportedOperationException("${javaClass.name} cannot revoke the tokens of a principal.")

    /**
     * The instant before which the tokens of [principal] in [tenant] are revoked, or `null` when none are.
     * The default, for stores without [revokeSubject], is `null`.
     */
    fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null

    /**
     * No-op token store that never revokes anything.
     *
     * This is the default wired by the Spring Boot starter, keeping verification
     * fully stateless: logout is inert until a real [TokenStore] bean is provided.
     * [revokeSubject] throws, as the interface default does.
     */
    object NoOp : TokenStore {
        override fun revoke(tokenId: String, ttl: Duration) = Unit
        override fun isRevoked(tokenId: String): Boolean = false
    }
}
