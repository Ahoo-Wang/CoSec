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

import me.ahoo.cache.annotation.toCoCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.TtlAt
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.reflect.KClass

/**
 * Policy and permission caches are the store of record in Redis (no CacheSource), so they must never expire.
 * Guards against CoCache's default TTL (3600s since 5.0) silently applying to them.
 */
class CacheTtlPolicyTest {

    @ParameterizedTest
    @ValueSource(
        classes = [PolicyCache::class, GlobalPolicyIndexCache::class, AppPermissionCache::class, RolePermissionCache::class]
    )
    fun storeOfRecordCachesNeverExpire(cacheType: Class<*>) {
        @Suppress("UNCHECKED_CAST")
        val metadata = (cacheType.kotlin as KClass<out Cache<*, *>>).toCoCacheMetadata()
        metadata.ttlPolicy.ttl.assert().isEqualTo(TtlAt.FOREVER)
        metadata.cacheName.assert().isEqualTo(cacheType.simpleName)
    }

    @ParameterizedTest
    @ValueSource(classes = [RevokedTokenCache::class])
    fun revokedTokenCacheBoundsMissingTtl(cacheType: Class<*>) {
        @Suppress("UNCHECKED_CAST")
        val metadata = (cacheType.kotlin as KClass<out Cache<*, *>>).toCoCacheMetadata()
        metadata.ttlPolicy.missingTtl.assert().isEqualTo(RevokedTokenCache.MISSING_TTL_SECONDS)
    }
}
