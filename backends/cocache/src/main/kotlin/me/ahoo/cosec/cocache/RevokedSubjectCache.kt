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
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * Revoked Subject Cache (RFC 0004 §4.5.3), a CoCache `Store` like [RevokedTokenCache].
 *
 * Key is [key] of a principal; value is the revocation's `issuedBefore` in epoch milliseconds: the principal's tokens
 * issued before it are revoked. Entries are written with their own lifetime, which outlives the refresh tokens they
 * revoke. Configured like [RevokedTokenCache], whose local tier settings (`cosec.authentication.revocation.cache.*`) it
 * shares; see [CoSecCacheSpecs.revokedSubject] for the declaration.
 *
 * @see CoCacheTokenStore
 */
interface RevokedSubjectCache : Store<String, Long> {
    companion object {
        /**
         * `{length of tenant}:{tenant}:{principal}`. The length keeps the key unambiguous whatever characters the ids
         * contain, and neither id may hold an unpaired UTF-16 surrogate, which the Redis key would write as `?`
         * (RC1-52, `IDN-10`): the key of `alice\uD800` would be the key of `alice?`.
         *
         * @throws IllegalArgumentException when [tenant] or [principal] holds one
         */
        fun key(tenant: TenantId, principal: PrincipalId): String {
            require(Identifiers.isWellFormed(tenant.value) && Identifiers.isWellFormed(principal.value)) {
                "Principal [${Identifiers.printable(principal.value)}] of tenant [${Identifiers.printable(tenant.value)}] " +
                    "names no key: an unpaired UTF-16 surrogate would share it with another id."
            }
            return "${tenant.value.length}:${tenant.value}:${principal.value}"
        }
    }
}
