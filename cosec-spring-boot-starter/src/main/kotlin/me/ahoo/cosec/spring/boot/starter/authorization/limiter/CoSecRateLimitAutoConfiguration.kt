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
package me.ahoo.cosec.spring.boot.starter.authorization.limiter

import me.ahoo.cosec.limiter.LocalRateLimitRuleLoader
import me.ahoo.cosec.limiter.LocalRateLimiter
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.limiter.RateLimiter
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * CoSec Rate Limit AutoConfiguration.
 *
 * Provides the rate limit rules (from [LimiterProperties.locations]) and, unless Redis supplied one, an in-memory
 * [RateLimiter]. [CoSecAuthorizationAutoConfiguration] wraps the authorization with them.
 *
 * @author ahoo wang
 */
@AutoConfiguration(
    after = [CoSecRedisRateLimiterAutoConfiguration::class],
    before = [CoSecAuthorizationAutoConfiguration::class],
)
@ConditionalOnCoSecEnabled
@ConditionalOnProperty(value = [LimiterProperties.ENABLED_KEY], matchIfMissing = true, havingValue = "true")
@EnableConfigurationProperties(LimiterProperties::class)
class CoSecRateLimitAutoConfiguration(private val limiterProperties: LimiterProperties) {

    @Bean
    @ConditionalOnMissingBean
    fun rateLimitRuleProvider(): RateLimitRuleProvider {
        return LocalRateLimitRuleLoader(limiterProperties.locations)
    }

    @Bean
    fun rateLimitRuleLoaderLifecycle(rateLimitRuleProvider: RateLimitRuleProvider): RateLimitRuleLoaderLifecycle {
        return RateLimitRuleLoaderLifecycle(rateLimitRuleProvider)
    }

    @Bean
    @ConditionalOnMissingBean
    fun localRateLimiter(): RateLimiter {
        return LocalRateLimiter()
    }
}
