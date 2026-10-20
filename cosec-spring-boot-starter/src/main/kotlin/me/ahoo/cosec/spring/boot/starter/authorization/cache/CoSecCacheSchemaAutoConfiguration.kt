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

import me.ahoo.cosec.cache.CoSecSchemaMarker
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Checks the CoSec data in Redis with [CoSecSchemaMarker.verify] before any CoSec cache is created: unmigrated 6.x
 * data stops startup instead of being read as missing. Skipped with `cocache.backend=memory`, which holds no 6.x data,
 * and with `cosec.authorization.cache.migration.check-schema=false`.
 */
@AutoConfiguration(
    afterName = [COCACHE_AUTO_CONFIGURATION, "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"],
    before = [
        CoSecPolicyCacheAutoConfiguration::class,
        CoSecPermissionCacheAutoConfiguration::class,
        CoSecTokenRevocationCacheAutoConfiguration::class,
    ]
)
@ConditionalOnCoSecEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.CoSecSchemaMarker"])
@ConditionalOnProperty(value = [MigrationConfiguration.CHECK_SCHEMA_KEY], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(value = ["cocache.backend"], havingValue = "redis", matchIfMissing = true)
@EnableConfigurationProperties(CacheProperties::class)
class CoSecCacheSchemaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate::class)
    fun coSecSchemaCheck(cacheProperties: CacheProperties, redisTemplate: StringRedisTemplate): CoSecSchemaCheck {
        val marker = CoSecSchemaMarker(redisTemplate, cacheProperties.keyPrefix, cacheProperties.legacyKeyPrefix)
        marker.verify()
        return CoSecSchemaCheck(marker)
    }
}

/**
 * Exists once [marker] was verified. CoSec's cache beans ask for it first ([awaitSchemaCheck]), so nothing reads or
 * writes Redis before the check.
 */
class CoSecSchemaCheck(val marker: CoSecSchemaMarker)

/** Runs the schema check, when it is configured, before the caller creates a cache. */
internal fun ObjectProvider<CoSecSchemaCheck>.awaitSchemaCheck() {
    getIfAvailable()
}
