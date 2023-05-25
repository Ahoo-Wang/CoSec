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
import me.ahoo.cache.CacheSource
import me.ahoo.cache.CacheSource.Companion.noOp
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cache.spring.redis.codec.SetToSetCodecExecutor
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.redis.GlobalPolicyIndexCache
import me.ahoo.cosec.redis.GlobalPolicyIndexKeyConverter
import me.ahoo.cosec.redis.PolicyCache
import me.ahoo.cosec.redis.RedisPolicyRepository
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosid.IdGenerator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * CoSec Policy Cache AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnCacheEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.redis.GlobalPolicyIndexCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
class CoSecPolicyCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

    companion object {
        const val GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME = "globalPolicyIndexCache"
        const val GLOBAL_POLICY_INDEX_CACHE_SOURCE_BEAN_NAME = "${GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME}Source"
        const val POLICY_CACHE_BEAN_NAME = "policyCache"
        const val POLICY_CACHE_SOURCE_BEAN_NAME = "${POLICY_CACHE_BEAN_NAME}Source"
    }

    @Bean
    @ConditionalOnMissingBean
    fun redisPolicyRepository(
        globalPolicyIndexCache: GlobalPolicyIndexCache,
        policyCache: PolicyCache
    ): PolicyRepository {
        return RedisPolicyRepository(
            globalPolicyIndexCache,
            policyCache,
        )
    }

    @Bean(GLOBAL_POLICY_INDEX_CACHE_SOURCE_BEAN_NAME)
    @ConditionalOnMissingBean(name = [GLOBAL_POLICY_INDEX_CACHE_SOURCE_BEAN_NAME])
    fun globalPolicyIndexCacheSource(): CacheSource<String, Set<String>> {
        return noOp()
    }

    @Bean
    @ConditionalOnMissingBean
    fun globalPolicyIndexCache(
        @Qualifier(GLOBAL_POLICY_INDEX_CACHE_SOURCE_BEAN_NAME) cacheSource:
        CacheSource<String, Set<String>>,
        redisTemplate: StringRedisTemplate,
        cacheManager: CacheManager,
        idGenerator: IdGenerator
    ): GlobalPolicyIndexCache {
        val clientId = idGenerator.generateAsString()
        val codecExecutor = SetToSetCodecExecutor(redisTemplate)
        val keyConverter = GlobalPolicyIndexKeyConverter(cacheProperties.cacheKeyPrefix.globalPolicyIndex)
        val distributedCaching: DistributedCache<Set<String>> = RedisDistributedCache(redisTemplate, codecExecutor)
        val delegate = cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME,
                clientId = clientId,
                keyConverter = keyConverter,
                distributedCaching = distributedCaching,
                cacheSource = cacheSource,
            ),
        )
        return GlobalPolicyIndexCache(delegate)
    }

    @Bean(POLICY_CACHE_SOURCE_BEAN_NAME)
    @ConditionalOnMissingBean(name = [POLICY_CACHE_SOURCE_BEAN_NAME])
    fun policyCacheSource(): CacheSource<String, Policy> {
        return noOp()
    }

    @Bean
    @ConditionalOnMissingBean
    fun policyCache(
        @Qualifier(POLICY_CACHE_SOURCE_BEAN_NAME) cacheSource: CacheSource<String, Policy>,
        redisTemplate: StringRedisTemplate,
        cacheManager: CacheManager,
        idGenerator: IdGenerator
    ): PolicyCache {
        val clientId = idGenerator.generateAsString()
        val cacheKeyPrefix = cacheProperties.cacheKeyPrefix.policy
        val codecExecutor = ObjectToJsonCodecExecutor(Policy::class.java, redisTemplate, CoSecJsonSerializer)
        val distributedCaching: DistributedCache<Policy> = RedisDistributedCache(redisTemplate, codecExecutor)
        val delegate = cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = POLICY_CACHE_BEAN_NAME,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(cacheKeyPrefix),
                distributedCaching = distributedCaching,
                cacheSource = cacheSource,
            ),
        )
        return PolicyCache(delegate)
    }
}
