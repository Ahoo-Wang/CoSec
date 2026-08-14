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

import me.ahoo.cosec.servlet.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.REACTIVE_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.SERVLET_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CoSecRequestParserAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            CoSecRequestParserAutoConfiguration::class.java,
        )

    @Test
    fun contextLoads() {
        contextRunner
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(CoSecRequestParserAutoConfiguration::class.java)
                    .hasBean(CoSecRequestParserAutoConfiguration.SERVLET_REMOTE_IP_RESOLVER_BEAN_NAME)
                    .hasBean(SERVLET_REQUEST_PARSER_BEAN_NAME)
                    .hasBean(CoSecRequestParserAutoConfiguration.REACTIVE_REMOTE_IP_RESOLVER_BEAN_NAME)
                    .hasBean(REACTIVE_REQUEST_PARSER_BEAN_NAME)
            }
    }

    @Test
    fun remoteIpResolverDefaultsToMaxTrustedIndexOne() {
        val autoConfiguration = CoSecRequestParserAutoConfiguration(AuthorizationProperties())
        val servletResolver =
            autoConfiguration.WebMvc().servletRemoteIpResolver() as ServletXForwardedRemoteIpResolver
        servletResolver.maxTrustedIndex.assert().isEqualTo(1)

        val reactiveResolver =
            autoConfiguration.WebFlux().reactiveRemoteIpResolver() as ReactiveXForwardedRemoteIpResolver
        reactiveResolver.maxTrustedIndex.assert().isEqualTo(1)
    }

    @Test
    fun remoteIpResolverRespectsMaxTrustedIndexProperty() {
        val properties = AuthorizationProperties().apply { remoteIp.maxTrustedIndex = 3 }
        val autoConfiguration = CoSecRequestParserAutoConfiguration(properties)
        val reactiveResolver =
            autoConfiguration.WebFlux().reactiveRemoteIpResolver() as ReactiveXForwardedRemoteIpResolver
        reactiveResolver.maxTrustedIndex.assert().isEqualTo(3)
    }

    @Test
    fun remoteIpResolverBindsMaxTrustedIndexZero() {
        contextRunner
            .withPropertyValues("cosec.authorization.remote-ip.max-trusted-index=0")
            .run { context: AssertableApplicationContext ->
                val reactiveResolver = context.getBean(
                    CoSecRequestParserAutoConfiguration.REACTIVE_REMOTE_IP_RESOLVER_BEAN_NAME,
                ) as ReactiveXForwardedRemoteIpResolver
                reactiveResolver.maxTrustedIndex.assert().isEqualTo(0)
                val servletResolver = context.getBean(
                    CoSecRequestParserAutoConfiguration.SERVLET_REMOTE_IP_RESOLVER_BEAN_NAME,
                ) as ServletXForwardedRemoteIpResolver
                servletResolver.maxTrustedIndex.assert().isEqualTo(0)
            }
    }

    @Test
    fun remoteIpResolverBindsMaxTrustedIndexThree() {
        contextRunner
            .withPropertyValues("cosec.authorization.remote-ip.max-trusted-index=3")
            .run { context: AssertableApplicationContext ->
                val reactiveResolver = context.getBean(
                    CoSecRequestParserAutoConfiguration.REACTIVE_REMOTE_IP_RESOLVER_BEAN_NAME,
                ) as ReactiveXForwardedRemoteIpResolver
                reactiveResolver.maxTrustedIndex.assert().isEqualTo(3)
                val servletResolver = context.getBean(
                    CoSecRequestParserAutoConfiguration.SERVLET_REMOTE_IP_RESOLVER_BEAN_NAME,
                ) as ServletXForwardedRemoteIpResolver
                servletResolver.maxTrustedIndex.assert().isEqualTo(3)
            }
    }
}
