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
@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.starter.apikey

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.RedisApiKeyReader
import me.ahoo.cosec.cache.RedisApiKeyStore
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.ApiKeyStore
import me.ahoo.cosec.spi.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spring.boot.starter.CoSecProperties
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.StartupCheck
import me.ahoo.cosec.spring.boot.starter.StartupCheckExclusion
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.ConditionalOnEnforceMode
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * API keys for machine clients, active only with `cosec.api-key.enabled=true`. Provides the [ApiKeyStore] keys are
 * managed through and, for Redis, the [ApiKeyReader] the pipeline reads them with ([CoSecPipelineAutoConfiguration]).
 */
@AutoConfiguration(
    before = [CoSecPipelineAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
    ],
)
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@ConditionalOnProperty(value = [ApiKeyProperties.ENABLED_KEY], havingValue = "true")
@EnableConfigurationProperties(ApiKeyProperties::class, CoSecProperties::class)
@Import(StartupCheckExclusion::class)
internal class CoSecApiKeyAutoConfiguration {

    /**
     * `IDN-6`: an API key's memberships are its principal's, from the `MembershipProvider` (RFC 0009 §4.2). Without one
     * every key would be a 503, so startup fails instead.
     */
    @Bean
    fun cosecApiKeyMembershipProviderCheck(context: ApplicationContext): StartupCheck = StartupCheck.OnCreation {
        check(context.getBeanNamesForType(MembershipProvider::class.java).isNotEmpty()) {
            "${ApiKeyProperties.ENABLED_KEY}=true needs a MembershipProvider bean: an API key's memberships are its " +
                "principal's (RFC 0009 §4.2). Add cosec-cocache and a MembershipLoader bean, which configures " +
                "CoCacheMembershipProvider, or declare a MembershipProvider of your own (IDN-6)."
        }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = [ApiKeyProperties.STORE_KEY], havingValue = "memory", matchIfMissing = true)
    fun apiKeyStore(apiKeyProperties: ApiKeyProperties): ApiKeyStore {
        val keys = apiKeyProperties.keys.map { it.hash to it.toApiKey() }
        val duplicates = keys.groupBy { it.second.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate API key ids: $duplicates." }
        // One secret is one key: two entries with its hash would leave the key with whichever came last (round 2 L5).
        val shared = keys.groupBy({ it.first }, { it.second.id }).values.filter { it.size > 1 }
        require(shared.isEmpty()) { "API keys ${shared.joinToString()} have the same hash: one key, configured twice." }
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
            cosec: CoSecProperties,
        ): ApiKeyStore = RedisApiKeyStore(stringRedisTemplate, CoSecCacheSpecs.apiKeyPrefix(cosec.keyPrefix))

        /** Reads without blocking, since the pipeline may look a key up on an event loop. */
        @Bean
        @ConditionalOnMissingBean
        fun redisApiKeyReader(
            redisTemplate: ReactiveStringRedisTemplate,
            cosec: CoSecProperties,
        ): ApiKeyReader = RedisApiKeyReader(redisTemplate, CoSecCacheSpecs.apiKeyPrefix(cosec.keyPrefix))
    }
}
