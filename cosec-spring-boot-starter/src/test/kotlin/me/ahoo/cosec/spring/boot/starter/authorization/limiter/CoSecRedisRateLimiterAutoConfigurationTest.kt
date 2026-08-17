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

import me.ahoo.cosec.cache.limiter.RedisGroupedRateLimiterConditionMatcherFactory
import me.ahoo.cosec.cache.limiter.RedisRateLimiterConditionMatcherFactory
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecRedisRateLimiterAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoadsWithRedis() {
        contextRunner
            .withUserConfiguration(
                DataRedisAutoConfiguration::class.java,
                CoSecRedisRateLimiterAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(LimiterProperties::class.java)
                    .hasSingleBean(CoSecRedisRateLimiterAutoConfiguration::class.java)
                    .hasSingleBean(RedisRateLimiterConditionMatcherFactory::class.java)
                    .hasSingleBean(RedisGroupedRateLimiterConditionMatcherFactory::class.java)
            }
    }

    @Test
    fun propertiesBindFromConfiguration() {
        contextRunner
            .withPropertyValues(
                "cosec.limiter.key-prefix=my-app:rl",
            )
            .withUserConfiguration(
                DataRedisAutoConfiguration::class.java,
                CoSecRedisRateLimiterAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                val properties = context.getBean(LimiterProperties::class.java)
                assertThat(properties.keyPrefix).isEqualTo("my-app:rl")
                assertThat(properties.enabled).isTrue()
            }
    }

    @Test
    fun notLoadedWhenDisabled() {
        contextRunner
            .withPropertyValues("cosec.limiter.enabled=false")
            .withUserConfiguration(
                DataRedisAutoConfiguration::class.java,
                CoSecRedisRateLimiterAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .doesNotHaveBean(CoSecRedisRateLimiterAutoConfiguration::class.java)
                    .doesNotHaveBean(RedisRateLimiterConditionMatcherFactory::class.java)
            }
    }

    @Test
    fun notLoadedWithoutRedisTemplate() {
        contextRunner
            .withUserConfiguration(CoSecRedisRateLimiterAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .doesNotHaveBean(RedisRateLimiterConditionMatcherFactory::class.java)
                    .doesNotHaveBean(RedisGroupedRateLimiterConditionMatcherFactory::class.java)
            }
    }
}
