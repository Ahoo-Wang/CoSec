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

package me.ahoo.cosec.cocache

import me.ahoo.cocache.api.Store
import me.ahoo.cosec.model.Identifiers

/**
 * Revoked Token Cache: a CoCache `Store`, since Redis holds the only copy of a revocation.
 *
 * Key is the token id (jti, [key]); value is `true` when the token has been revoked.
 * Entries are written with their own lifetime, the remaining lifetime of the revoked token's bound refresh token.
 *
 * The local tier is configured via `cosec.authentication.revocation.cache.*`
 * (default: 30s local max age, 100k entries); the distributed tier is Redis.
 * See [CoSecCacheSpecs.revokedToken] for the declaration.
 *
 * @author ahoo wang
 * @see CoCacheTokenStore
 */
interface RevokedTokenCache : Store<String, Boolean> {
    companion object {
        /** Bounds how long a "not revoked" lookup is cached locally if an invalidation message is lost. */
        const val MISSING_TTL_SECONDS = 30L

        /**
         * The key of the token [tokenId]: the id itself, which may not hold an unpaired UTF-16 surrogate, since the Redis
         * key would write it as `?` (RC1-52, `IDN-10`): revoking `jti\uD800` would revoke `jti?`.
         *
         * @throws IllegalArgumentException when [tokenId] holds one
         */
        fun key(tokenId: String): String {
            require(Identifiers.isWellFormed(tokenId)) {
                "Token [${Identifiers.printable(tokenId)}] names no key: an unpaired UTF-16 surrogate would share it with " +
                    "another token."
            }
            return tokenId
        }
    }
}
