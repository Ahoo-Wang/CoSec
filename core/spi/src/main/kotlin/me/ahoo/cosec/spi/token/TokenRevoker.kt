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
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import java.time.Instant

/**
 * Revokes tokens for logout.
 *
 * The application exposes its own logout endpoint and calls [revoke] with the access token to invalidate. Both calls
 * suspend and never block the calling thread.
 *
 * @see TokenStore
 */
@CoSecSpi
interface TokenRevoker {

    /**
     * Logs out: revokes the access token and its session (RFC 0009), so no pair of the session refreshes any more, the
     * pairs it was refreshed from included. Other sessions of the principal are untouched; [revokeSubject] ends them all.
     * An older generation's access token stays valid until it expires, as every access token does.
     *
     * @param accessToken the access token to revoke, valid and unexpired
     * @return what the revoked token said
     * @throws TokenVerificationException if the token is invalid (including expired: log out with [revoke] of the pair
     * then) or already revoked
     */
    @Throws(TokenVerificationException::class)
    suspend fun revoke(accessToken: String): VerifiedToken

    /**
     * Logs out with the pair, for a client whose access token has expired: the refresh token is verified and must be
     * bound to the access token, as a refresh requires; then as [revoke] of the access token.
     */
    @Throws(TokenVerificationException::class)
    suspend fun revoke(token: CompositeToken): VerifiedToken

    /**
     * Revokes every token of [principal] in [tenant] issued before [issuedBefore], access and refresh tokens alike:
     * the emergency revocation that memberships read from token claims rely on (RFC 0004 §4.5.3). Tokens issued
     * later, such as after the principal logs in again, are accepted, unless [issuedBefore] lies in the future.
     *
     * Like [revoke], it takes effect wherever tokens are checked against a [RevocationReader] over the same
     * [TokenStore]. The default implementation throws.
     *
     * @throws UnsupportedOperationException if the revoker or its [TokenStore] cannot record it
     */
    suspend fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant = Instant.now()): Unit =
        throw UnsupportedOperationException("${javaClass.name} cannot revoke the tokens of a principal.")
}
