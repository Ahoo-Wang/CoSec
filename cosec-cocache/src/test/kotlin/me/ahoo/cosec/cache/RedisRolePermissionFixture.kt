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

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.LocalCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.SetToSetCodecExecutor
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosid.test.MockIdGenerator
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Role grants in a real Redis (`localhost:6379`) under a random key prefix, read through real CoCache caches the way
 * the starter wires them: [RolePermissionCache] keys are `<prefix>:app:role:permission:<appId>:<roleId>[@<spaceId>]`.
 * Every cache shares one eviction bus, so caches made by [rolePermissionCache] behave like separate instances.
 */
class RedisRolePermissionFixture : AutoCloseable {
    private class TestAppPermissionCache(cache: CoherentCache<String, AppPermission>) :
        AppPermissionCache,
        CoherentCache<String, AppPermission> by cache

    private class TestRolePermissionCache(cache: CoherentCache<AppSpacedRoleId, Set<PermissionId>>) :
        RolePermissionCache,
        CoherentCache<AppSpacedRoleId, Set<PermissionId>> by cache

    private val lettuceConnectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
    val redisTemplate = StringRedisTemplate(lettuceConnectionFactory)
    val keyPrefix = "test:" + MockIdGenerator.INSTANCE.generateAsString()
    val rolePermissionKeyPrefix = "$keyPrefix:app:role:permission:"
    val legacyRolePermissionKeyPrefix = "$keyPrefix:role:permission:"
    private val eventBus = LocalCacheEvictedEventBus()
    private val caches = mutableListOf<CoherentCache<*, *>>()

    val appPermissionCache: AppPermissionCache = TestAppPermissionCache(
        coherentCache("AppPermissionCache", "instance", ToStringKeyConverter(""), InMemoryDistributedCache()),
    )

    /** A new instance's view of the role grants: its own local cache over the shared Redis. */
    fun rolePermissionCache(clientId: String = "instance"): RolePermissionCache = TestRolePermissionCache(
        coherentCache(
            "RolePermissionCache",
            clientId,
            ToStringKeyConverter(rolePermissionKeyPrefix),
            RedisDistributedCache(redisTemplate, SetToSetCodecExecutor(redisTemplate)),
        ),
    )

    fun repository(rolePermissionCache: RolePermissionCache = rolePermissionCache()) =
        RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)

    private fun <K, V> coherentCache(
        name: String,
        clientId: String,
        keyConverter: ToStringKeyConverter<K>,
        distributedCache: DistributedCache<V>
    ): CoherentCache<K, V> = DefaultCoherentCache(
        CoherentCacheConfiguration(
            cacheName = name,
            clientId = clientId,
            keyConverter = keyConverter,
            distributedCache = distributedCache,
            clientSideCache = CaffeineClientSideCache.build(),
            cacheSource = CacheSource.noOp(),
            ttlPolicy = TtlPolicy(ttl = TtlAt.FOREVER),
        ),
        eventBus,
    ).also {
        eventBus.register(it)
        caches.add(it)
    }

    override fun close() {
        caches.forEach { it.close() }
        redisTemplate.keys("$keyPrefix:*").takeIf { it.isNotEmpty() }?.let { redisTemplate.delete(it) }
        lettuceConnectionFactory.destroy()
    }
}
