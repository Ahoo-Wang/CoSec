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

package me.ahoo.cosec.spring.boot.starter.inject

import io.mockk.mockk
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.servlet.InjectSecurityContextFilter
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.ConditionalOnAuthorizationEnabled
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosec.webflux.ReactiveInjectSecurityContextWebFilter
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class InjectSecurityContextAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoadsWhenDefault() {
        contextRunner
            .withUserConfiguration(
                CoSecRequestParserAutoConfiguration::class.java,
                InjectSecurityContextAutoConfiguration::class.java
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .doesNotHaveBean(InjectSecurityContextProperties::class.java)
                    .doesNotHaveBean(InjectSecurityContextAutoConfiguration::class.java)
            }
    }

    @Test
    fun contextLoadsWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "${ConditionalOnInjectSecurityEnabled.ENABLED_KEY}=true",
                "${ConditionalOnAuthorizationEnabled.ENABLED_KEY}=false",
            )
            .withUserConfiguration(
                CoSecRequestParserAutoConfiguration::class.java,
                InjectSecurityContextAutoConfiguration::class.java
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(InjectSecurityContextProperties::class.java)
                    .hasSingleBean(InjectSecurityContextAutoConfiguration::class.java)
                    .hasSingleBean(InjectSecurityContextFilter::class.java)
                    .hasSingleBean(ReactiveInjectSecurityContextWebFilter::class.java)
            }
    }

    @Test
    fun contextLoadsWhenEnabledUsesUnverifiedParserOnlyWithoutAuthorization() {
        contextRunner
            .withPropertyValues(
                "${ConditionalOnInjectSecurityEnabled.ENABLED_KEY}=true",
                "${ConditionalOnAuthorizationEnabled.ENABLED_KEY}=false",
            )
            .withUserConfiguration(
                CoSecRequestParserAutoConfiguration::class.java,
                InjectSecurityContextAutoConfiguration::class.java
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context.getBean(SecurityContextParser::class.java))
                    .isSameAs(InjectSecurityContextParser)
            }
    }

    /**
     * With authorization and inject both on, the parser must verify signatures. User configurations are
     * registered in the given order without sorting, so both orders prove the result does not rest on
     * class names or registration order.
     */
    @Test
    fun verifyingParserWinsWhenAuthorizationIsEnabledInAnyOrder() {
        val authorizationFirst = arrayOf(
            CoSecRequestParserAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
            InjectSecurityContextAutoConfiguration::class.java,
        )
        val injectFirst = arrayOf(
            CoSecRequestParserAutoConfiguration::class.java,
            InjectSecurityContextAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
        )
        listOf(authorizationFirst, injectFirst).forEach { configurations ->
            authorizationAndInjectRunner()
                .withUserConfiguration(*configurations)
                .run(::assertVerifyingParser)
        }
    }

    @Test
    fun verifyingParserWinsWhenAuthorizationIsEnabledAsAutoConfigurations() {
        authorizationAndInjectRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    InjectSecurityContextAutoConfiguration::class.java,
                    CoSecRequestParserAutoConfiguration::class.java,
                    CoSecAuthorizationAutoConfiguration::class.java,
                )
            )
            .run(::assertVerifyingParser)
    }

    private fun authorizationAndInjectRunner(): ApplicationContextRunner = contextRunner
        .withPropertyValues("${ConditionalOnInjectSecurityEnabled.ENABLED_KEY}=true")
        .withBean(TokenVerifier::class.java, { mockk<TokenVerifier>() })
        .withBean(PolicyRepository::class.java, { mockk<PolicyRepository>() })
        .withBean(AppRolePermissionRepository::class.java, { mockk<AppRolePermissionRepository>() })

    private fun assertVerifyingParser(context: AssertableApplicationContext) {
        AssertionsForInterfaceTypes.assertThat(context)
            .hasNotFailed()
            .hasSingleBean(SecurityContextParser::class.java)
            .hasSingleBean(InjectSecurityContextFilter::class.java)
        AssertionsForInterfaceTypes.assertThat(context.getBean(SecurityContextParser::class.java))
            .isNotSameAs(InjectSecurityContextParser)
            .isExactlyInstanceOf(DefaultSecurityContextParser::class.java)
    }
}
