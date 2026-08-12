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

import me.ahoo.cosec.api.policy.PolicyStore
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

/**
 * CoSec Policy Cache AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnCacheEnabled
@ConditionalOnClass(
    name = [
        "me.ahoo.cosec.cache.PolicyCache",
        "org.springframework.data.redis.core.StringRedisTemplate",
    ]
)
@EnableConfigurationProperties(
    CacheProperties::class,
)
class CoSecPolicyCacheAutoConfiguration(private val cacheProperties: CacheProperties) {
    companion object {
        const val POLICY_CACHE_BEAN_NAME = "PolicyCache"
    }

    @Bean
    @ConditionalOnMissingBean
    fun redisPolicyRepository(
        policyStore: PolicyStore,
    ): PolicyRepository {
        return RedisPolicyRepository(policyStore)
    }

    @Bean(POLICY_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean(PolicyStore::class)
    fun policyStore(
        redisTemplate: StringRedisTemplate,
        objectMapper: ObjectMapper,
    ): RedisPolicyStore {
        return RedisPolicyStore(
            redisTemplate,
            objectMapper,
            cacheProperties.policyStoreKey,
            cacheProperties.globalPolicyStoreKey,
            cacheProperties.policyKeyPrefix,
            cacheProperties.globalPolicyIndexKey,
        )
    }
}
