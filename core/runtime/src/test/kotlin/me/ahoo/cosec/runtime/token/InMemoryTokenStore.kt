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
import me.ahoo.cosec.spi.token.TokenStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A real [TokenStore] for tests: entries never expire, and the lifetimes asked for are recorded.
 */
internal class InMemoryTokenStore : TokenStore {
    val revokedTokens = ConcurrentHashMap<String, Duration>()
    val revokedSubjects = ConcurrentHashMap<Pair<String, String>, Pair<Instant, Duration>>()

    override fun revoke(tokenId: String, ttl: Duration) {
        revokedTokens[tokenId] = ttl
    }

    override fun isRevoked(tokenId: String): Boolean = revokedTokens.containsKey(tokenId)

    override fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) {
        revokedSubjects.merge(tenant.value to principal.value, issuedBefore to ttl) { existing, new ->
            if (existing.first.isBefore(new.first)) new else existing
        }
    }

    override fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
        revokedSubjects[tenant.value to principal.value]?.first
}
