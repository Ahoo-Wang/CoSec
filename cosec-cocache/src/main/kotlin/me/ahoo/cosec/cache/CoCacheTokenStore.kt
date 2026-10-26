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

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
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
     * first. Two instances revoking the same principal at once may both write; the later write wins.
     */
    override fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) {
        val subjectCache = subjectCache ?: return super.revokeSubject(tenant, principal, issuedBefore, ttl)
        val key = RevokedSubjectCache.key(tenant, principal)
        val revokedBefore = issuedBefore.toEpochMilli()
        val existing = subjectCache.get(key)
        if (existing != null && existing >= revokedBefore) {
            return
        }
        subjectCache.put(key, revokedBefore, ttl.coerceAtLeast(Duration.ZERO).plusSeconds(1))
    }

    /**
     * @return the instant before which the principal's tokens are revoked; `null` on a miss, or without [subjectCache]
     */
    override fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
        subjectCache?.get(RevokedSubjectCache.key(tenant, principal))?.let(Instant::ofEpochMilli)
}
