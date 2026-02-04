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

import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.EnableCoCache
import me.ahoo.cache.spring.client.SpringClientSideCacheFactory.Companion.CLIENT_SIDE_CACHE_SUFFIX
import me.ahoo.cache.spring.converter.SpringKeyConverterFactory.Companion.KEY_CONVERTER_SUFFIX
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.RedisDistributedCacheFactory.Companion.DISTRIBUTED_CACHE_SUFFIX
import me.ahoo.cache.spring.redis.codec.SetToSetCodecExecutor
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.cache.AppPermissionCache
import me.ahoo.cosec.cache.RedisAppRolePermissionRepository
import me.ahoo.cosec.cache.RolePermissionCache
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
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
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.AppPermissionCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
@EnableCoCache(caches = [AppPermissionCache::class, RolePermissionCache::class])
class CoSecPermissionCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

    companion object {
        const val APP_PERMISSION_CACHE_BEAN_NAME = "AppPermissionCache"
        const val APP_PERMISSION_CACHE_KEY_CONVERTER_BEAN_NAME =
            "${APP_PERMISSION_CACHE_BEAN_NAME}$KEY_CONVERTER_SUFFIX"
        const val ROLE_PERMISSION_CACHE_BEAN_NAME = "RolePermissionCache"
        const val ROLE_PERMISSION_CACHE_KEY_CONVERTER_BEAN_NAME =
            "${ROLE_PERMISSION_CACHE_BEAN_NAME}$KEY_CONVERTER_SUFFIX"
        const val ROLE_PERMISSION_CACHE_CLIENT_BEAN_NAME =
            "${ROLE_PERMISSION_CACHE_BEAN_NAME}$CLIENT_SIDE_CACHE_SUFFIX"
        const val ROLE_PERMISSION_CACHE_DISTRIBUTED_CACHE_BEAN_NAME =
            "${ROLE_PERMISSION_CACHE_BEAN_NAME}$DISTRIBUTED_CACHE_SUFFIX"
    }

    @Bean
    @ConditionalOnMissingBean
    fun rolePermissionRepository(
        appPermissionCache: AppPermissionCache,
        rolePermissionCache: RolePermissionCache
    ): AppRolePermissionRepository {
        return RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)
    }

    @Bean(APP_PERMISSION_CACHE_KEY_CONVERTER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [APP_PERMISSION_CACHE_KEY_CONVERTER_BEAN_NAME])
    fun appPermissionCacheKeyConverter(): KeyConverter<String> {
        return ToStringKeyConverter(cacheProperties.appPermissionKeyPrefix)
    }

    @Bean(ROLE_PERMISSION_CACHE_KEY_CONVERTER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [ROLE_PERMISSION_CACHE_KEY_CONVERTER_BEAN_NAME])
    fun rolePermissionCacheKeyConverter(): KeyConverter<SpacedRoleId> {
        return ToStringKeyConverter(cacheProperties.rolePermissionKeyPrefix)
    }

    @Bean(ROLE_PERMISSION_CACHE_CLIENT_BEAN_NAME)
    @ConditionalOnMissingBean(name = [ROLE_PERMISSION_CACHE_CLIENT_BEAN_NAME])
    fun policyCacheClientSideCache(): ClientSideCache<Policy> {
        return cacheProperties.role.toGuavaClientSideCache()
    }

    @Bean(ROLE_PERMISSION_CACHE_DISTRIBUTED_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean(name = [ROLE_PERMISSION_CACHE_DISTRIBUTED_CACHE_BEAN_NAME])
    fun rolePermissionCacheDistributedCache(redisTemplate: StringRedisTemplate): DistributedCache<Set<String>> {
        val codecExecutor = SetToSetCodecExecutor(redisTemplate)
        return RedisDistributedCache(redisTemplate, codecExecutor)
    }
}
