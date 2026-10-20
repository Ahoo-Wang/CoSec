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
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.cache.RedisPolicyRevisionStore
import me.ahoo.cosec.policy.history.PolicyHistory
import me.ahoo.cosec.policy.history.PolicyRevisionStore
import me.ahoo.cosec.policy.history.VersionedPolicyRepository
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

/**
 * CoSec Policy Cache AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration(afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnCacheEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.GlobalPolicyIndexCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
class CoSecPolicyCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

    companion object {
        const val GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME = CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME
        const val POLICY_CACHE_BEAN_NAME = CoSecCacheSpecs.POLICY_CACHE_NAME
        const val POLICY_HISTORY_ENABLED_KEY = "${CacheProperties.PREFIX}.history.enabled"
    }

    @Bean
    @ConditionalOnMissingBean
    fun redisPolicyRepository(
        globalPolicyIndexCache: GlobalPolicyIndexCache,
        policyCache: PolicyCache,
        policyRevisionStore: ObjectProvider<PolicyRevisionStore>
    ): PolicyRepository {
        val repository = RedisPolicyRepository(
            globalPolicyIndexCache,
            policyCache,
        )
        val revisionStore = policyRevisionStore.ifAvailable ?: return repository
        return VersionedPolicyRepository(repository, revisionStore)
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = [POLICY_HISTORY_ENABLED_KEY], havingValue = "true")
    fun redisPolicyRevisionStore(redisTemplate: StringRedisTemplate): PolicyRevisionStore =
        RedisPolicyRevisionStore(
            redisTemplate,
            cacheProperties.policyHistoryKeyPrefix,
            cacheProperties.history.maxRevisions
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = [POLICY_HISTORY_ENABLED_KEY], havingValue = "true")
    fun policyHistory(policyRepository: PolicyRepository, policyRevisionStore: PolicyRevisionStore): PolicyHistory =
        PolicyHistory(policyRepository, policyRevisionStore)

    @Bean(GLOBAL_POLICY_INDEX_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun globalPolicyIndexCache(
        runtime: CacheRuntime,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        cocacheProperties: ObjectProvider<CoCacheProperties>,
    ): GlobalPolicyIndexCache {
        val spec = CoSecCacheSpecs.globalPolicyIndex(cacheProperties.keyPrefix)
        return cosecCacheAssembler(runtime, customizers, cocacheProperties).createGlobalPolicyIndex(spec)
    }

    /**
     * Policies are encoded with the application's [ObjectMapper], as before.
     */
    @Bean(POLICY_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun policyCache(
        runtime: CacheRuntime,
        objectMapper: ObjectMapper,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        cocacheProperties: ObjectProvider<CoCacheProperties>,
    ): PolicyCache {
        val spec = CoSecCacheSpecs.policy(
            CoSecCacheSpecs.jacksonCodec<Policy>(objectMapper),
            cacheProperties.keyPrefix,
            cacheProperties.policy.toCachePolicy(CachePolicy.DEFAULT),
        )
        return cosecCacheAssembler(runtime, customizers, cocacheProperties).create(PolicyCache::class.java, spec)
    }
}
