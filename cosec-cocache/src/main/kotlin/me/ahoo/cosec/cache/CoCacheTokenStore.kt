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
 * @author ahoo wang
 */
class CoCacheTokenStore(private val cache: RevokedTokenCache) : TokenStore {

    override fun revoke(tokenId: String, ttl: Duration) {
        // CacheValue.ttlAt 的单位是秒；+1 向上取整，保证条目不早于 token 自然过期
        cache.set(tokenId, Instant.now().epochSecond + ttl.toSeconds() + 1, true)
    }

    override fun isRevoked(tokenId: String): Boolean = cache.get(tokenId) == true
}
