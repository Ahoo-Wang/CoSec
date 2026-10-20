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

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Role grants in a real Redis (`localhost:6379`) under a random key prefix, read through real CoCache caches the way
 * the starter declares them: [RolePermissionCache] keys are `<prefix>:app:role:permission:<appId>:<roleId>[@<spaceId>]`.
 * Every cache made by [rolePermissionCache] runs on its own [CacheRuntime], so it behaves like a separate instance.
 */
class RedisRolePermissionFixture : AutoCloseable {
    private val lettuceConnectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
    val redisTemplate = StringRedisTemplate(lettuceConnectionFactory)
    val keyPrefix = CoSecTestCaches.newKeyPrefix()
    val rolePermissionKeyPrefix = "$keyPrefix:app:role:permission:"
    val legacyRolePermissionKeyPrefix = "$keyPrefix:role:permission:"
    private val backend = CoSecTestCaches.redisBackend()
    private val runtimes = mutableListOf<CacheRuntime>()

    val appPermissionCache: AppPermissionCache = runtime().createCache(
        CoSecCacheSpecs.appPermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix)
    )

    /** A new instance's view of the role grants: its own local cache over the shared Redis. */
    @Suppress("UnusedParameter")
    fun rolePermissionCache(clientId: String = "instance"): RolePermissionCache = runtime().createCache(
        CoSecCacheSpecs.rolePermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix)
    )

    /** The grants Redis holds for [key], read by a fresh instance. */
    fun grants(key: AppSpacedRoleId): Set<PermissionId>? = rolePermissionCache("reader")[key]

    fun repository(rolePermissionCache: RolePermissionCache = rolePermissionCache()) =
        RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)

    private fun runtime(): CacheRuntime = CacheRuntime.builder().backend(backend).build().also { runtimes.add(it) }

    override fun close() {
        runtimes.forEach { it.close() }
        backend.close()
        redisTemplate.keys("$keyPrefix:*").takeIf { it.isNotEmpty() }?.let { redisTemplate.delete(it) }
        lettuceConnectionFactory.destroy()
    }
}
