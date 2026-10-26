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
package me.ahoo.cosec.cache

import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.token.RevocationReader
import me.ahoo.cosec.token.TokenStore
import java.time.Duration
import java.time.Instant

/**
 * [TokenStore] backed by a CoCache two-level cache (local + Redis),
 * so revocation is shared across instances.
 *
 * Local hits cost no network; cross-instance propagation is bounded by the
 * local cache TTL (default 30 seconds, `cosec.authorization.cache.token.*`).
 *
 * Revoking all tokens of a principal ([revokeSubject]) needs [subjectCache]; without it, it throws.
 *
 * @author ahoo wang
 * @see RevokedTokenCache
 * @see RevokedSubjectCache
 */
class CoCacheTokenStore @JvmOverloads constructor(
    private val cache: RevokedTokenCache,
    private val subjectCache: RevokedSubjectCache? = null
) : TokenStore {
    /** The same revocations, read without blocking, for the coroutine pipeline (RFC 0006 §5.3.2). */
    val reader: RevocationReader = CoCacheRevocationReader(cache, subjectCache)

    /**
     * Writes the revocation with an explicit lifetime; the write invalidates any
     * "not revoked" answer other instances cached locally for this token id.
     *
     * @param tokenId the token id (jti) to revoke
     * @param ttl how long to keep the entry
     */
    override fun revoke(tokenId: String, ttl: Duration) {
        // the extra second keeps the entry past the token's natural expiry (CoCache needs a positive lifetime)
        cache.put(tokenId, true, ttl.coerceAtLeast(Duration.ZERO).plusSeconds(1))
    }

    /**
     * @param tokenId the token id (jti) to check
     * @return true if the token has been revoked; false on a miss
     */
    override fun isRevoked(tokenId: String): Boolean = cache.get(tokenId) == true

    /**
     * Keeps the later of the existing and the new `issuedBefore`, so that a second revocation never shortens the
     * first. The read bypasses the local copy, which may be stale or a cached miss, and the write is conditional on
     * what was read, so two instances revoking the same principal at once cannot replace a later entry with an earlier
     * one. A Redis failure throws rather than writing blind.
     */
    override fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) {
        val subjectCache = subjectCache
            ?: throw UnsupportedOperationException("CoCacheTokenStore needs a RevokedSubjectCache to revoke a principal.")
        val key = RevokedSubjectCache.key(tenant, principal)
        val revokedBefore = epochMilliRoundedUp(issuedBefore)
        val entryTtl = ttl.coerceAtLeast(Duration.ZERO).plusSeconds(1)
        repeat(MAX_REVOKE_SUBJECT_ATTEMPTS) {
            val current = when (val read = subjectCache.getIfChanged(key, null)) {
                is ConditionalGet.Present -> read
                ConditionalGet.Absent -> null
                ConditionalGet.Unchanged -> error("A read with no known digest cannot be unchanged.")
            }
            if (current != null && current.value >= revokedBefore) {
                return
            }
            when (subjectCache.putIfMatch(key, current?.digest, revokedBefore, entryTtl)) {
                is ConditionalPut.Written -> return
                is ConditionalPut.Conflict -> Unit
            }
        }
        throw IllegalStateException(
            "Could not revoke the tokens of principal [${principal.value}] in tenant [${tenant.value}]: " +
                "the entry kept changing for $MAX_REVOKE_SUBJECT_ATTEMPTS attempts."
        )
    }

    /**
     * @return the instant before which the principal's tokens are revoked; `null` on a miss, or without [subjectCache]
     */
    override fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
        subjectCache?.get(RevokedSubjectCache.key(tenant, principal))?.let(Instant::ofEpochMilli)

    private companion object {
        const val MAX_REVOKE_SUBJECT_ATTEMPTS = 8

        /**
         * Millisecond precision would otherwise move `issuedBefore` back by up to a millisecond, and a token issued in
         * that millisecond, before the revocation, at a whole second (`iat`), would no longer count as revoked.
         */
        fun epochMilliRoundedUp(instant: Instant): Long {
            val millis = instant.toEpochMilli()
            return if (instant.nano % NANOS_PER_MILLI == 0) millis else millis + 1
        }

        const val NANOS_PER_MILLI = 1_000_000
    }
}
