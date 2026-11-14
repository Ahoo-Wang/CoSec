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
 * Where revocations are written: a token by its [VerifiedToken.id] (jti), or every token of a principal at once
 * ([revokeSubject]). What was written is read through a [RevocationReader] over the same store, by the pipeline, by
 * refresh and by [TokenRevoker], all with one judgement (`TokenRevocation`).
 *
 * Writes suspend and never block the calling thread, which may be an event loop. A write that fails throws, so a
 * logout or an emergency revocation never silently does nothing.
 *
 * @see RevocationReader
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
    suspend fun revoke(tokenId: String, ttl: Duration)

    /**
     * Revokes every token of [principal] in [tenant] issued before [issuedBefore] (RFC 0004 §4.5.3).
     *
     * A later call with an earlier [issuedBefore] must not shorten an existing entry. Stores that cannot record it
     * throw, so that an emergency revocation never silently does nothing.
     *
     * @param ttl how long to keep the entry; must cover the lifetime of the refresh tokens issued until [issuedBefore]
     * @throws UnsupportedOperationException if the store does not support it, as the default does
     */
    suspend fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration): Unit =
        throw UnsupportedOperationException("${javaClass.name} cannot revoke the tokens of a principal.")
}
