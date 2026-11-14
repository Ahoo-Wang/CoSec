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

package me.ahoo.cosec.runtime.token

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The [TokenRevoker] over a [TokenStore]: [revoke] verifies the token, refuses one already revoked ([revocation]), ends
 * its session in [families] and records its id; [revokeSubject] records the principal's revocation. A revocation store that cannot be read does not
 * stop a logout: whether the token was already revoked is then unknown, and revoking it again is harmless.
 *
 * @param tokenVerifier verifies the token and resolves its id before revoking
 * @param tokenStore where the revocation entry is recorded
 * @param revocation the judgement that refuses a token already revoked, over the same store
 * @param families where the token's session is ended, so no pair of it refreshes any more
 * @param revocationTtl how long each revocation entry is kept; must cover the full refresh token validity, so it never
 * expires before the bound refresh token does: the starter wires it from `cosec.jwt.token-validity.refresh`
 * @param clock the time [revokeSubject] measures a future `issuedBefore` from
 */
class DefaultTokenRevoker @JvmOverloads constructor(
    private val tokenVerifier: TokenVerifier,
    private val tokenStore: TokenStore,
    private val revocation: TokenRevocation,
    private val families: TokenFamilies,
    private val revocationTtl: Duration,
    private val clock: Clock = Clock.systemUTC()
) : TokenRevoker {

    override suspend fun revoke(accessToken: String): VerifiedToken = revoked(tokenVerifier.verify(accessToken))

    override suspend fun revoke(token: CompositeToken): VerifiedToken = revoked(tokenVerifier.refresh(token))

    private suspend fun revoked(token: VerifiedToken): VerifiedToken {
        try {
            revocation.check(token)
        } catch (_: RevocationUnavailableException) {
            // Not known to be revoked yet: record it, which the write's own failure would report.
        }
        token.session?.let { families.end(it.id) }
        tokenStore.revoke(token.id, revocationTtl)
        return token
    }

    /**
     * The entry is kept for [revocationTtl] past [issuedBefore], so it outlives every refresh token it revokes.
     */
    override suspend fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant) {
        val untilIssuedBefore = Duration.between(clock.instant(), issuedBefore).coerceAtLeast(Duration.ZERO)
        tokenStore.revokeSubject(tenant, principal, issuedBefore, revocationTtl + untilIssuedBefore)
    }
}
