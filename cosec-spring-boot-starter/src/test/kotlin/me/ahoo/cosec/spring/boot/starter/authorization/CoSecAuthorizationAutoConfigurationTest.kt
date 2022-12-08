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

package me.ahoo.cosec.spring.boot.starter.authorization

import me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.servlet.AuthorizationFilter
import me.ahoo.cosec.spring.boot.starter.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CoSecCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.token.TokenCompositeAuthentication
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.test.MockIdGenerator
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecAuthorizationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withUserConfiguration(
                RedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecCacheAutoConfiguration::class.java,
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecAuthorizationAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(AuthorizationProperties::class.java)
                    .hasSingleBean(CoSecAuthorizationAutoConfiguration::class.java)
                    .hasSingleBean(Authorization::class.java)
                    .hasSingleBean(TokenCompositeAuthentication::class.java)
                    .hasBean(CoSecAuthorizationAutoConfiguration.SERVLET_REQUEST_TENANT_ID_PARSER_BEAN_NAME)
                    .hasBean(CoSecAuthorizationAutoConfiguration.SERVLET_REQUEST_PARSER_BEAN_NAME)
                    .hasBean(CoSecAuthorizationAutoConfiguration.SERVLET_SECURITY_CONTEXT_PARSER_BEAN_NAME)
                    .hasSingleBean(AuthorizationFilter::class.java)
                    .hasBean(CoSecAuthorizationAutoConfiguration.REACTIVE_REQUEST_TENANT_ID_PARSER_BEAN_NAME)
                    .hasBean(CoSecAuthorizationAutoConfiguration.REACTIVE_REQUEST_PARSER_BEAN_NAME)
                    .hasBean(CoSecAuthorizationAutoConfiguration.REACTIVE_SECURITY_CONTEXT_PARSER_BEAN_NAME)
//                    .hasSingleBean(ReactiveAuthorizationFilter::class.java)
            }
    }
}
