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

import me.ahoo.cosec.cache.limiter.RedisRateLimiter
import me.ahoo.cosec.limiter.RateLimiter
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * CoSec Redis Rate Limiter AutoConfiguration.
 *
 * Registers the cluster-wide [RedisRateLimiter] as the [RateLimiter] when Redis is on the classpath and a
 * [StringRedisTemplate] bean exists; otherwise [CoSecRateLimitAutoConfiguration] falls back to the in-memory one.
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [DataRedisAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnProperty(value = [LimiterProperties.ENABLED_KEY], matchIfMissing = true, havingValue = "true")
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.limiter.RedisRateLimiter"])
@ConditionalOnBean(StringRedisTemplate::class)
@EnableConfigurationProperties(LimiterProperties::class)
class CoSecRedisRateLimiterAutoConfiguration(private val limiterProperties: LimiterProperties) {

    @Bean
    @ConditionalOnMissingBean(RateLimiter::class)
    fun cosecRedisRateLimiter(stringRedisTemplate: StringRedisTemplate): RateLimiter {
        return RedisRateLimiter(stringRedisTemplate, limiterProperties.keyPrefix)
    }
}
