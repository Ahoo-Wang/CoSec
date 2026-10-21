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
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cloud.gateway.support.ConfigurationService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.ConversionService
import org.springframework.validation.Validator
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter as GatewayRedisRateLimiter

/**
 * Spring Cloud Gateway registers its own `redisRateLimiter` bean; CoSec's must not take the same name.
 */
internal class CoSecRedisRateLimiterGatewayTest {

    @Test
    fun startsNextToSpringCloudGatewayRedisRateLimiter() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataRedisAutoConfiguration::class.java,
                    DataRedisReactiveAutoConfiguration::class.java,
                    // Package-private in Spring Cloud Gateway.
                    Class.forName("org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"),
                    CoSecRedisRateLimiterAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(GatewaySupport::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(RateLimiter::class.java)).isInstanceOf(RedisRateLimiter::class.java)
                assertThat(context).hasSingleBean(GatewayRedisRateLimiter::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    class GatewaySupport {
        @Bean
        fun gatewayConfigurationService(
            beanFactory: BeanFactory,
            conversionService: ObjectProvider<ConversionService>,
            validator: ObjectProvider<Validator>,
        ): ConfigurationService = ConfigurationService(beanFactory, conversionService, validator)
    }
}
