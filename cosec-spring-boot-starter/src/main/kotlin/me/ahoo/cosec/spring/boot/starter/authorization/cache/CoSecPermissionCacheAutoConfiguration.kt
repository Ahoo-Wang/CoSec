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

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.cache.AppPermissionCache
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.RedisAppRolePermissionRepository
import me.ahoo.cosec.cache.RolePermissionCache
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper

/**
 * CoSec Permission Cache AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration(afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnCacheEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.AppPermissionCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
class CoSecPermissionCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

    companion object {
        const val APP_PERMISSION_CACHE_BEAN_NAME = CoSecCacheSpecs.APP_PERMISSION_CACHE_NAME
        const val ROLE_PERMISSION_CACHE_BEAN_NAME = CoSecCacheSpecs.ROLE_PERMISSION_CACHE_NAME
    }

    @Bean
    @ConditionalOnMissingBean
    fun rolePermissionRepository(
        appPermissionCache: AppPermissionCache,
        rolePermissionCache: RolePermissionCache
    ): AppRolePermissionRepository {
        return RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)
    }

    /**
     * App permissions are encoded with the application's [ObjectMapper], as before. They keep CoCache's default
     * local cache settings; `cocache.caches.AppPermissionCache.*` tunes them.
     */
    @Bean(APP_PERMISSION_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun appPermissionCache(
        runtime: CacheRuntime,
        objectMapper: ObjectMapper,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        cocacheProperties: ObjectProvider<CoCacheProperties>,
    ): AppPermissionCache {
        val spec = CoSecCacheSpecs.appPermission(
            CoSecCacheSpecs.jacksonCodec<AppPermission>(objectMapper),
            cacheProperties.keyPrefix,
        )
        return cosecCacheAssembler(runtime, customizers, cocacheProperties).create(AppPermissionCache::class.java, spec)
    }

    @Bean(ROLE_PERMISSION_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun rolePermissionCache(
        runtime: CacheRuntime,
        objectMapper: ObjectMapper,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        cocacheProperties: ObjectProvider<CoCacheProperties>,
    ): RolePermissionCache {
        val spec = CoSecCacheSpecs.rolePermission(
            CoSecCacheSpecs.jacksonCodec<Set<PermissionId>>(objectMapper),
            cacheProperties.keyPrefix,
            cacheProperties.role.toCachePolicy(CachePolicy.DEFAULT),
        )
        return cosecCacheAssembler(
            runtime,
            customizers,
            cocacheProperties
        ).create(RolePermissionCache::class.java, spec)
    }
}
