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
     * Revokes the access token so that neither it nor the refresh token bound to it can be used any more. Only that
     * pair: a pair it was refreshed from, or one refreshed from that, stays usable; [revokeSubject] ends them all.
     *
     * @param accessToken the access token to revoke
     * @return what the revoked token said
     * @throws TokenVerificationException if the token is invalid (including
     * already expired -- revoke before the access token expires to also kill
     * the bound refresh token) or already revoked
     */
    @Throws(TokenVerificationException::class)
    suspend fun revoke(accessToken: String): VerifiedToken

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
