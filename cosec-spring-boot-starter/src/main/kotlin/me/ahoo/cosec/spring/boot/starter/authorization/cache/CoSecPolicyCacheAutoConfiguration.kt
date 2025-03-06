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
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.GlobalPolicyIndexKeyConverter
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
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
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.GlobalPolicyIndexCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
@EnableCoCache(caches = [GlobalPolicyIndexCache::class, PolicyCache::class])
class CoSecPolicyCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

    companion object {
        const val GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME = "GlobalPolicyIndexCache"
        const val GLOBAL_POLICY_INDEX_CACHE_KEY_CONVERTER_BEAN_NAME =
            "${GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME}$KEY_CONVERTER_SUFFIX"
        const val GLOBAL_POLICY_INDEX_CACHE_DISTRIBUTED_CACHE_BEAN_NAME =
            "${GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME}$DISTRIBUTED_CACHE_SUFFIX"
        const val POLICY_CACHE_BEAN_NAME = "PolicyCache"
        const val POLICY_CACHE_KEY_CONVERTER_BEAN_NAME = "${POLICY_CACHE_BEAN_NAME}$KEY_CONVERTER_SUFFIX"
        const val POLICY_CACHE_CLIENT_BEAN_NAME = "${POLICY_CACHE_BEAN_NAME}$CLIENT_SIDE_CACHE_SUFFIX"
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

    @Bean(GLOBAL_POLICY_INDEX_CACHE_KEY_CONVERTER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [GLOBAL_POLICY_INDEX_CACHE_KEY_CONVERTER_BEAN_NAME])
    fun globalPolicyIndexCacheKeyConverter(): KeyConverter<String> {
        return GlobalPolicyIndexKeyConverter(cacheProperties.globalPolicyIndexKey)
    }

    @Bean(GLOBAL_POLICY_INDEX_CACHE_DISTRIBUTED_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean(name = [GLOBAL_POLICY_INDEX_CACHE_DISTRIBUTED_CACHE_BEAN_NAME])
    fun globalPolicyIndexCacheDistributedCache(redisTemplate: StringRedisTemplate): DistributedCache<Set<String>> {
        val codecExecutor = SetToSetCodecExecutor(redisTemplate)
        return RedisDistributedCache(redisTemplate, codecExecutor)
    }

    @Bean(POLICY_CACHE_CLIENT_BEAN_NAME)
    @ConditionalOnMissingBean(name = [POLICY_CACHE_CLIENT_BEAN_NAME])
    fun policyCacheClientSideCache(): ClientSideCache<Policy> {
        return cacheProperties.policy.toGuavaClientSideCache()
    }

    @Bean(POLICY_CACHE_KEY_CONVERTER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [POLICY_CACHE_KEY_CONVERTER_BEAN_NAME])
    fun policyCacheKeyConverter(): KeyConverter<String> {
        return ToStringKeyConverter(cacheProperties.policyKeyPrefix)
    }
}
