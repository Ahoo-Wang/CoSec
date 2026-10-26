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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosid.test.MockIdGenerator

/**
 * One CoSec instance: its own [CacheRuntime] (local caches) over a [backend] that other instances may share.
 * Real CoCache engines throughout, never mocks.
 */
internal class CoSecTestCaches(
    backend: CoherenceBackend = InMemoryBackend(),
    val keyPrefix: String = newKeyPrefix(),
) : AutoCloseable {
    val runtime: CacheRuntime = CacheRuntime.builder().backend(backend).build()

    val policyCache: PolicyCache by lazy {
        runtime.createCache(CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix))
    }
    val globalPolicyIndexCache: GlobalPolicyIndexCache by lazy {
        runtime.createGlobalPolicyIndex(CoSecCacheSpecs.globalPolicyIndex(keyPrefix))
    }
    val appPermissionCache: AppPermissionCache by lazy {
        runtime.createCache(CoSecCacheSpecs.appPermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix))
    }
    val rolePermissionCache: RolePermissionCache by lazy {
        runtime.createCache(
            CoSecCacheSpecs.rolePermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix)
        )
    }
    val revokedTokenCache: RevokedTokenCache by lazy {
        runtime.createCache(CoSecCacheSpecs.revokedToken(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix))
    }

    val revokedSubjectCache: RevokedSubjectCache by lazy {
        runtime.createCache(
            CoSecCacheSpecs.revokedSubject(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix)
        )
    }

    val membershipCache: MembershipCache by lazy { runtime.createCache(CoSecCacheSpecs.membership(keyPrefix)) }

    fun policyRepository() = RedisPolicyRepository(globalPolicyIndexCache, policyCache)

    fun appRolePermissionRepository() = RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)

    /** Drops this instance's local copies only; the shared backend keeps the values. */
    fun clearLocal(cacheName: String) {
        checkNotNull(runtime.admin(cacheName)).clearLocal()
    }

    override fun close() {
        runtime.close()
    }

    companion object {
        fun newKeyPrefix(): String = "test:" + MockIdGenerator.INSTANCE.generateAsString()

        /** The Redis the tests run against (`localhost:6379`). */
        fun redisBackend(): RedisBackend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"))
    }
}
