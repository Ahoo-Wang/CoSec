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

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import java.time.Duration
import java.time.Instant

/**
 * The read side of a [TokenStore] for the coroutine pipeline (RFC 0006 §5.3.2): the same two questions, as suspend
 * functions that never block the calling thread, which may be an event loop.
 *
 * It sits next to [TokenStore] rather than replacing its methods (RFC 0006 Q11) because the 7.x path still reads
 * [TokenStore] synchronously through [RevocableTokenVerifier] until the switch removes that path (RFC 0008 §4.4).
 *
 * A store that cannot be read follows the documented exception to "fail closed" (AGENTS.md): it answers "not revoked"
 * itself, as `CoSecCacheSpecs` configures the revocation caches to do. Anything an implementation throws fails the
 * request with 503.
 */
interface RevocationReader {
    /** Whether the token with id [tokenId] (jti) has been revoked. */
    suspend fun isRevoked(tokenId: String): Boolean

    /** The instant before which the tokens of [principal] in [tenant] are revoked, or null when none are. */
    suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant?

    companion object {
        /** Nothing is ever revoked: the default when no [TokenStore] is configured, like [TokenStore.NoOp]. */
        val NONE: RevocationReader = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = false

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
        }
    }
}

/** RFC 0004 §4.5.3: the one rule for revoking all tokens of a principal, read by both token paths. */
object SubjectRevocation {
    /**
     * Whether a token issued at [issuedAt] is revoked by a revocation of its principal's tokens issued before
     * [revokedBefore]. A token that does not say when it was issued cannot prove it is newer, so it is revoked too.
     * JWT `iat` has whole seconds, which rounds a token's age up: a token issued in the same second as the revocation,
     * even just after it, is revoked as well.
     */
    fun revokes(revokedBefore: Instant?, issuedAt: Instant?): Boolean = revokes(revokedBefore, issuedAt, Duration.ZERO)

    /**
     * As [revokes], also revoking tokens issued up to [skew] after [revokedBefore]: an issuer whose clock runs ahead of
     * the one that recorded the revocation stamps a token issued before the revocation with a later `iat`. Revoking a
     * little too much is the fail-safe direction: a principal who signs in again within [skew] of the revocation must
     * sign in once more.
     */
    fun revokes(revokedBefore: Instant?, issuedAt: Instant?, skew: Duration): Boolean {
        require(!skew.isNegative) { "The revocation skew must not be negative, not $skew." }
        return revokedBefore != null && (issuedAt == null || issuedAt.isBefore(revokedBefore.plus(skew)))
    }
}
