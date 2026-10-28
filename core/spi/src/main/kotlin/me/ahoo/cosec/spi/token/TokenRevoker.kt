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

import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Revokes tokens for logout.
 *
 * The application exposes its own logout endpoint and calls [revoke] with the
 * token to invalidate. Passing a [me.ahoo.cosec.api.token.CompositeToken]
 * (a subtype of [AccessToken]) requires no separate overload.
 *
 * @see DefaultTokenRevoker
 * @see TokenStore
 */
interface TokenRevoker {

    /**
     * Revokes the access token so that neither it nor the refresh token bound
     * to it can be used any more.
     *
     * @param accessToken the access token to revoke
     * @return the principal of the revoked token
     * @throws TokenVerificationException if the token is invalid (including
     * already expired -- revoke before the access token expires to also kill
     * the bound refresh token) or already revoked
     */
    @Throws(TokenVerificationException::class)
    fun revoke(accessToken: AccessToken): TokenPrincipal

    /**
     * Revokes every token of [principal] in [tenant] issued before [issuedBefore], access and refresh tokens alike:
     * the emergency revocation that memberships read from token claims rely on (RFC 0004 §4.5.3). Tokens issued
     * later, such as after the principal logs in again, are accepted, unless [issuedBefore] lies in the future.
     *
     * Like [revoke], it takes effect only where tokens are verified by a `RevocableTokenVerifier` over the same
     * [TokenStore]. The default implementation throws.
     *
     * @throws UnsupportedOperationException if the revoker or its [TokenStore] cannot record it
     */
    fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant = Instant.now()): Unit =
        throw UnsupportedOperationException("${javaClass.name} cannot revoke the tokens of a principal.")
}

/**
 * Default [TokenRevoker] backed by a [TokenStore].
 *
 * @param tokenVerifier verifies the token and resolves its tokenId before revoking
 * @param tokenStore where the revocation entry is recorded
 * @param revocationTtl how long each revocation entry is kept; must cover the
 * full refresh token validity, so it never expires before the bound refresh
 * token does -- the starter wires it from `cosec.jwt.token-validity.refresh`
 * @param clock the time [revokeSubject] measures a future `issuedBefore` from
 */
class DefaultTokenRevoker @JvmOverloads constructor(
    private val tokenVerifier: TokenVerifier,
    private val tokenStore: TokenStore,
    private val revocationTtl: Duration,
    private val clock: Clock = Clock.systemUTC()
) : TokenRevoker {

    override fun revoke(accessToken: AccessToken): TokenPrincipal {
        val tokenPrincipal = tokenVerifier.verify<TokenPrincipal>(accessToken)
        tokenStore.revoke(tokenPrincipal.tokenId, revocationTtl)
        return tokenPrincipal
    }

    /**
     * The entry is kept for [revocationTtl] past [issuedBefore], so it outlives every refresh token it revokes.
     */
    override fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant) {
        val untilIssuedBefore = Duration.between(clock.instant(), issuedBefore).coerceAtLeast(Duration.ZERO)
        tokenStore.revokeSubject(tenant, principal, issuedBefore, revocationTtl + untilIssuedBefore)
    }
}
