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

package me.ahoo.cosec.spring.boot.starter.authorization.gateway

import me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
import me.ahoo.cosec.gateway.AuthorizationGatewayFilter
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CoSecPermissionCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CoSecPolicyCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.test.MockIdGenerator
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecGatewayAuthorizationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withUserConfiguration(
                RedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPolicyCacheAutoConfiguration::class.java,
                CoSecPermissionCacheAutoConfiguration::class.java,
                CoSecAuthorizationAutoConfiguration::class.java,
                CoSecGatewayAuthorizationAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(GatewayProperties::class.java)
                    .hasSingleBean(CoSecGatewayAuthorizationAutoConfiguration::class.java)
                    .hasSingleBean(AuthorizationGatewayFilter::class.java)
            }
    }

    @Test
    fun contextLoadsWhenDisable() {
        contextRunner
            .withPropertyValues("${ConditionalOnGatewayEnabled.ENABLED_KEY}=false")
            .withUserConfiguration(
                CoSecGatewayAuthorizationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .doesNotHaveBean(GatewayProperties::class.java)
            }
    }
}
