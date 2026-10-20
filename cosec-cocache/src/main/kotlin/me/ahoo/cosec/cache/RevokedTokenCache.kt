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

import me.ahoo.cocache.api.Cache

/**
 * Revoked Token Cache .
 *
 * Key is the token id (jti); value is `true` when the token has been revoked.
 * Entries are written with their own lifetime, the remaining lifetime of the revoked token's bound refresh token.
 *
 * The local tier is configured via `cosec.authorization.cache.token.*`
 * (default: 30s local max age, 100k entries); the distributed tier is Redis.
 * See [CoSecCacheSpecs.revokedToken] for the declaration.
 *
 * @author ahoo wang
 * @see CoCacheTokenStore
 */
interface RevokedTokenCache : Cache<String, Boolean> {
    companion object {
        /** Bounds how long a "not revoked" lookup is cached locally if an invalidation message is lost. */
        const val MISSING_TTL_SECONDS = 30L
    }
}
