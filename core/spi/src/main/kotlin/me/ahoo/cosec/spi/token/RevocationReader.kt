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
 * The read side of a [TokenStore] (RFC 0006 §5.3.2): whether a token, or every token of its principal, was revoked, as
 * suspend functions that never block the calling thread, which may be an event loop. The pipeline, refresh and
 * [TokenRevoker] all read it, through one judgement (`TokenRevocation` in `cosec-runtime`).
 *
 * A reader reports what it knows and never decides what a failure means: when its store cannot be read it throws
 * [RevocationUnavailableException], never "not revoked". The caller decides: the pipeline's `TokenAuthenticator` lets the
 * token it verifies through (the documented exception to failing closed, AGENTS.md), refresh fails (`IDN-14`). Anything
 * else it throws is an error, which fails the request with 503.
 */
@CoSecSpi
interface RevocationReader {
    /**
     * Whether the token with id [tokenId] (jti) has been revoked.
     *
     * @throws RevocationUnavailableException when the store cannot be read
     */
    suspend fun isRevoked(tokenId: String): Boolean

    /**
     * The instant before which the tokens of [principal] in [tenant] are revoked, or null when none are.
     *
     * @throws RevocationUnavailableException when the store cannot be read
     */
    suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant?

    companion object {
        /** Nothing is ever revoked: for a host that verifies tokens no one revokes. */
        val NONE: RevocationReader = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = false

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
        }
    }
}

/**
 * RFC 0004 §4.5.3: the one rule for revoking all tokens of a principal. Every token path reads it with the same skew
 * (`cosec.authentication.revocation.skew`), through `TokenRevocation`: the pipeline's `TokenAuthenticator` and
 * refresh, so a token the pipeline refuses cannot be refreshed into one it accepts.
 */
object SubjectRevocation {
    /**
     * Five seconds: clocks kept by NTP agree within milliseconds, so this covers an issuer drifting well beyond that,
     * while a principal who signs in again just after a revocation waits at most five seconds.
     */
    val DEFAULT_SKEW: Duration = Duration.ofSeconds(5)

    /**
     * Whether a token issued at [issuedAt] is revoked by a revocation of its principal's tokens issued before
     * [revokedBefore], or up to [skew] after it: an issuer whose clock runs ahead of the one that recorded the
     * revocation stamps a token issued before the revocation with a later `iat`. Revoking a little too much is the
     * fail-safe direction: a principal who signs in again within [skew] of the revocation must sign in once more.
     * A token that does not say when it was issued cannot prove it is newer, so it is revoked too. JWT `iat` has whole
     * seconds, which rounds a token's age up: a token issued in the same second as the revocation is revoked as well.
     */
    fun revokes(revokedBefore: Instant?, issuedAt: Instant?, skew: Duration): Boolean {
        require(!skew.isNegative) { "The revocation skew must not be negative, not $skew." }
        return revokedBefore != null && (issuedAt == null || issuedAt.isBefore(revokedBefore.plus(skew)))
    }
}
