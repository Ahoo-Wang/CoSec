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
package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cache.CacheConfig
import me.ahoo.cache.CacheManager
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.api.source.CacheSource.Companion.noOp
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cache.spring.redis.codec.SetToSetCodecExecutor
import me.ahoo.cache.util.ClientIdGenerator
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.redis.AppPermissionCache
import me.ahoo.cosec.redis.RedisAppRolePermissionRepository
import me.ahoo.cosec.redis.RolePermissionCache
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * CoSec Permission Cache AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnCacheEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.redis.AppPermissionCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
class CoSecPermissionCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

    companion object {
        const val APP_PERMISSION_CACHE_BEAN_NAME = "appPermissionCache"
        const val APP_PERMISSION_CACHE_SOURCE_BEAN_NAME = "${APP_PERMISSION_CACHE_BEAN_NAME}Source"
        const val Role_PERMISSION_CACHE_BEAN_NAME = "rolePermissionCache"
        const val Role_PERMISSION_CACHE_SOURCE_BEAN_NAME = "${Role_PERMISSION_CACHE_BEAN_NAME}Source"
    }

    @Bean
    @ConditionalOnMissingBean
    fun rolePermissionRepository(
        appPermissionCache: AppPermissionCache,
        rolePermissionCache: RolePermissionCache
    ): AppRolePermissionRepository {
        return RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)
    }

    @Bean(APP_PERMISSION_CACHE_SOURCE_BEAN_NAME)
    @ConditionalOnMissingBean(name = [APP_PERMISSION_CACHE_SOURCE_BEAN_NAME])
    fun appPermissionCacheSource(): CacheSource<String, AppPermission> {
        return noOp()
    }

    @Bean
    @ConditionalOnMissingBean
    fun appPermissionCache(
        @Qualifier(APP_PERMISSION_CACHE_SOURCE_BEAN_NAME) cacheSource: CacheSource<String, AppPermission>,
        redisTemplate: StringRedisTemplate,
        cacheManager: CacheManager,
        clientIdGenerator: ClientIdGenerator
    ): AppPermissionCache {
        val clientId = clientIdGenerator.generate()
        val cacheKeyPrefix = cacheProperties.appPermissionKeyPrefix
        val codecExecutor = ObjectToJsonCodecExecutor(AppPermission::class.java, redisTemplate, CoSecJsonSerializer)
        val distributedCaching: DistributedCache<AppPermission> = RedisDistributedCache(redisTemplate, codecExecutor)
        val delegate = cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = APP_PERMISSION_CACHE_BEAN_NAME,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(cacheKeyPrefix),
                distributedCaching = distributedCaching,
                cacheSource = cacheSource,
            ),
        )
        return AppPermissionCache(delegate)
    }

    @Bean(Role_PERMISSION_CACHE_SOURCE_BEAN_NAME)
    @ConditionalOnMissingBean(name = [Role_PERMISSION_CACHE_SOURCE_BEAN_NAME])
    fun rolePermissionCacheSource(): CacheSource<String, Set<String>> {
        return noOp()
    }

    @Bean
    @ConditionalOnMissingBean
    fun rolePermissionCache(
        @Qualifier(Role_PERMISSION_CACHE_SOURCE_BEAN_NAME) cacheSource: CacheSource<String, Set<String>>,
        redisTemplate: StringRedisTemplate,
        cacheManager: CacheManager,
        clientIdGenerator: ClientIdGenerator
    ): RolePermissionCache {
        val clientId = clientIdGenerator.generate()
        val cacheKeyPrefix = cacheProperties.rolePermissionKeyPrefix
        val codecExecutor = SetToSetCodecExecutor(redisTemplate)
        val distributedCaching: DistributedCache<Set<String>> = RedisDistributedCache(redisTemplate, codecExecutor)
        val delegate = cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = Role_PERMISSION_CACHE_BEAN_NAME,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(cacheKeyPrefix),
                distributedCaching = distributedCaching,
                cacheSource = cacheSource,
            ),
        )
        return RolePermissionCache(delegate)
    }
}
