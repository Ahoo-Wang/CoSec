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

import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.TokenPrincipal
import java.time.Duration

/**
 * Revokes tokens for logout.
 *
 * The application exposes its own logout endpoint and calls [revoke] with the
 * token to invalidate. Passing a [me.ahoo.cosec.api.token.CompositeToken]
 * (a subtype of [AccessToken]) requires no separate overload.
 */
interface TokenRevoker {

    /**
     * Revokes the access token so that neither it nor the refresh token bound
     * to it can be used any more.
     *
     * @param accessToken the access token to revoke
     * @return the principal of the revoked token
     * @throws TokenVerificationException if the token is invalid or already revoked
     */
    @Throws(TokenVerificationException::class)
    fun revoke(accessToken: AccessToken): TokenPrincipal
}

/**
 * Default [TokenRevoker] backed by a [TokenStore].
 *
 * @param revocationTtl must cover the full refresh token validity, so the
 * revocation entry never expires before the bound refresh token does
 */
class DefaultTokenRevoker(
    private val tokenVerifier: TokenVerifier,
    private val tokenStore: TokenStore,
    private val revocationTtl: Duration
) : TokenRevoker {

    override fun revoke(accessToken: AccessToken): TokenPrincipal {
        val tokenPrincipal = tokenVerifier.verify<TokenPrincipal>(accessToken)
        tokenStore.revoke(tokenPrincipal.tokenId, revocationTtl)
        return tokenPrincipal
    }
}
