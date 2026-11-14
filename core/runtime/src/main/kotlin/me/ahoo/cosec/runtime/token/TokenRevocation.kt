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

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.SubjectRevocation
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Duration

/**
 * The one judgement of whether a verified token was revoked (RFC 0004 §4.5.3, `MEM-4`): its id, or every token of its
 * principal issued before a revocation, read from [revocations] with the same [skew] wherever a token is checked. The
 * pipeline's `TokenAuthenticator`, refresh and [DefaultTokenRevoker] all ask it, so a token one of them refuses
 * none of them accepts.
 *
 * It never decides what a failed read means: whatever [revocations] throws reaches the caller, an unavailable store as
 * [me.ahoo.cosec.spi.token.RevocationUnavailableException]. The pipeline's verification may let a token through on it
 * (PIP-7); refresh, which issues one, never does (`IDN-14`).
 *
 * @param skew `cosec.authentication.revocation.skew`: how far ahead of the revoking clock an issuer's clock may run
 */
class TokenRevocation(
    private val revocations: RevocationReader,
    private val skew: Duration = SubjectRevocation.DEFAULT_SKEW,
) {
    init {
        require(!skew.isNegative) { "The revocation skew must not be negative, not $skew." }
    }

    /**
     * Returns when [token] is not revoked.
     *
     * @throws TokenRevokedException when its id, or its principal's tokens issued before it, were revoked
     * @throws me.ahoo.cosec.spi.token.RevocationUnavailableException when the revocations cannot be read
     */
    suspend fun check(token: VerifiedToken) {
        if (revocations.isRevoked(token.id)) {
            throw TokenRevokedException("Token [${token.id}] has been revoked.")
        }
        val tenant = token.tenant?.let(::TenantId) ?: TenantId.DEFAULT
        val revokedBefore = revocations.subjectRevokedBefore(tenant, PrincipalId(token.principal))
        if (SubjectRevocation.revokes(revokedBefore, token.issuedAt, skew)) {
            throw TokenRevokedException(
                "The tokens of principal [${token.principal}] issued before [$revokedBefore] have been revoked."
            )
        }
    }
}
