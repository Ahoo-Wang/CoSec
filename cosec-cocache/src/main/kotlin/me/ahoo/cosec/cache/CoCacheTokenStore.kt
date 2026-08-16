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
 * @author ahoo wang
 * @see RevokedTokenCache
 */
class CoCacheTokenStore(private val cache: RevokedTokenCache) : TokenStore {

    /**
     * Writes the revocation with an explicit absolute expiry, overwriting any
     * missing-guard the lookup tier may have cached for this token id.
     *
     * @param tokenId the token id (jti) to revoke
     * @param ttl how long to keep the entry
     */
    override fun revoke(tokenId: String, ttl: Duration) {
        // CacheValue.ttlAt is epoch SECONDS; the +1 rounds up so the entry
        // never expires before the token's natural expiry
        cache.set(tokenId, Instant.now().epochSecond + ttl.toSeconds() + 1, true)
    }

    /**
     * @param tokenId the token id (jti) to check
     * @return true if the token has been revoked; false on a miss
     */
    override fun isRevoked(tokenId: String): Boolean = cache.get(tokenId) == true
}
