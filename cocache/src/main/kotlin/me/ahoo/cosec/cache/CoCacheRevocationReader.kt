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

import kotlinx.coroutines.future.await
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.token.RevocationReader
import java.time.Instant

/**
 * The [RevocationReader] over the caches [CoCacheTokenStore] writes: the non-blocking reads the coroutine pipeline makes
 * (RFC 0006 §5.3.2), where [CoCacheTokenStore] answers the 7.x path synchronously.
 *
 * Both reads go through CoCache's async view. A local hit answers on the caller's thread without suspending (RFC 0006
 * T1); a miss reads Redis without blocking any thread and completes on CoCache's async pool, from which the pipeline
 * moves on (T2). The caches keep CoCache's degraded read mode: a failed read is a miss, so the token counts as not
 * revoked, the documented exception to failing closed ([CoSecCacheSpecs.revokedToken]).
 */
class CoCacheRevocationReader @JvmOverloads constructor(
    private val cache: RevokedTokenCache,
    private val subjectCache: RevokedSubjectCache? = null,
) : RevocationReader {
    override suspend fun isRevoked(tokenId: String): Boolean = cache.async().get(tokenId).await() == true

    /** Null without [subjectCache]: then no principal's tokens can have been revoked through this store. */
    override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? {
        val subjectCache = subjectCache ?: return null
        return subjectCache.async().get(RevokedSubjectCache.key(tenant, principal)).await()?.let(Instant::ofEpochMilli)
    }
}
