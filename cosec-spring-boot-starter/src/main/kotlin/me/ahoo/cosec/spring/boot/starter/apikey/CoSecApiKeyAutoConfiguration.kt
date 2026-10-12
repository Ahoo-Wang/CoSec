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
package me.ahoo.cosec.spring.boot.starter.apikey

import me.ahoo.cosec.apikey.ApiKeyStore
import me.ahoo.cosec.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.cache.RedisApiKeyStore
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * API keys for machine clients, active only with `cosec.api-key.enabled=true`. Provides the [ApiKeyStore];
 * [CoSecAuthorizationAutoConfiguration] then resolves keys ahead of the token parser.
 */
@AutoConfiguration(
    before = [CoSecAuthorizationAutoConfiguration::class],
    afterName = ["org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"],
)
@ConditionalOnCoSecEnabled
@ConditionalOnProperty(value = [ApiKeyProperties.ENABLED_KEY], havingValue = "true")
@EnableConfigurationProperties(ApiKeyProperties::class)
class CoSecApiKeyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = [ApiKeyProperties.STORE_KEY], havingValue = "memory", matchIfMissing = true)
    fun apiKeyStore(apiKeyProperties: ApiKeyProperties): ApiKeyStore {
        val keys = apiKeyProperties.keys.map { it.hash to it.toApiKey() }
        val duplicates = keys.groupBy { it.second.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate API key ids: $duplicates." }
        return InMemoryApiKeyStore(keys.toMap())
    }

    @Configuration
    @ConditionalOnProperty(value = [ApiKeyProperties.STORE_KEY], havingValue = "redis")
    @ConditionalOnClass(name = ["me.ahoo.cosec.cache.RedisApiKeyStore"])
    class Redis {
        @Bean
        @ConditionalOnMissingBean
        fun redisApiKeyStore(
            stringRedisTemplate: StringRedisTemplate,
            apiKeyProperties: ApiKeyProperties
        ): ApiKeyStore = RedisApiKeyStore(stringRedisTemplate, apiKeyProperties.keyPrefix)
    }
}
