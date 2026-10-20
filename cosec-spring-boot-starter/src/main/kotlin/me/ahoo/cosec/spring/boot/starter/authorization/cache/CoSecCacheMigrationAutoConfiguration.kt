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

import me.ahoo.cocache.redis.migration.CoCache5MigrationOptions
import me.ahoo.cocache.redis.migration.CoCache5Migrator
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCoCache5Migration
import me.ahoo.cosec.cache.RevokedTokenCache
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

/**
 * The one-off migration of CoSec 6.x (CoCache 5) data to 7.0, see [CoSecCoCache5Migration]. It does nothing on its own:
 * operators call it with every writer stopped (see the upgrading guide). Role grants have their own migration,
 * `RolePermissionKeyMigration`.
 */
@AutoConfiguration(
    after = [
        CoSecPolicyCacheAutoConfiguration::class,
        CoSecPermissionCacheAutoConfiguration::class,
        CoSecTokenRevocationCacheAutoConfiguration::class,
    ]
)
@ConditionalOnCoSecEnabled
@ConditionalOnCacheEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.CoSecCoCache5Migration"])
@EnableConfigurationProperties(CacheProperties::class)
class CoSecCacheMigrationAutoConfiguration {

    /**
     * Built from the same declarations as the caches, so values are written exactly as the caches read them. The
     * migrator reuses the application's Lettuce client and opens its own connection for each operation.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate::class)
    fun coSecCoCache5Migration(
        cacheProperties: CacheProperties,
        redisTemplate: StringRedisTemplate,
        redisConnectionFactory: RedisConnectionFactory,
        objectMapper: ObjectMapper,
        revokedTokenCache: ObjectProvider<RevokedTokenCache>,
    ): CoSecCoCache5Migration {
        val keyPrefix = cacheProperties.keyPrefix
        val options = CoCache5MigrationOptions(
            backupPrefix = cacheProperties.migration.backupPrefix,
            missingGuardSentinel = cacheProperties.migration.missingGuardSentinel,
        )
        return CoSecCoCache5Migration(
            migrator = {
                val lettuce = checkNotNull(redisConnectionFactory as? LettuceConnectionFactory) {
                    "The CoCache 5 migration needs Lettuce, but the RedisConnectionFactory is " +
                        "${redisConnectionFactory.javaClass.name}."
                }
                CoCache5Migrator.of(
                    checkNotNull(lettuce.nativeClient) { "The Lettuce client is not started." },
                    options
                )
            },
            redisTemplate = redisTemplate,
            policySpec = CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(objectMapper), keyPrefix),
            globalPolicyIndexSpec = CoSecCacheSpecs.globalPolicyIndex(keyPrefix),
            appPermissionSpec = CoSecCacheSpecs.appPermission(CoSecCacheSpecs.jacksonCodec(objectMapper), keyPrefix),
            revokedTokenSpec = revokedTokenCache.ifAvailable?.let {
                CoSecCacheSpecs.revokedToken(CoSecCacheSpecs.jacksonCodec(objectMapper), keyPrefix)
            },
            legacyKeyPrefix = cacheProperties.migration.legacyKeyPrefix ?: keyPrefix,
        )
    }
}
