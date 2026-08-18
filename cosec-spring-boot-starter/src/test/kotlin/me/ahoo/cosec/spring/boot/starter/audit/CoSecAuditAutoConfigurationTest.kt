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

package me.ahoo.cosec.spring.boot.starter.audit

import io.mockk.mockk
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.audit.AuditingAuthorization
import me.ahoo.cosec.audit.LoggingAuditEventSink
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.function.Supplier

@Configuration(proxyBeanMethods = false)
class AutoConfiguredAuthorizationTestConfiguration {
    @Bean(CoSecAuthorizationAutoConfiguration.BEAN_NAME)
    @CoSecAuthorizationAutoConfiguration.AutoConfigured
    fun cosecAuthorization(): Authorization = mockk()
}

class CoSecAuditAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun auditOnWhenOpenTelemetryAbsent() {
        contextRunner
            .withClassLoader(FilteredClassLoader("me.ahoo.cosec.opentelemetry.CoSecInstrumenter"))
            .withUserConfiguration(
                AutoConfiguredAuthorizationTestConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
                CoSecAuditFallbackAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBeansOfType(AuditingAuthorization::class.java).assert().hasSize(1)
                context.getBeansOfType(LoggingAuditEventSink::class.java).assert().hasSize(1)
                val authorization = context.getBean(Authorization::class.java)
                authorization.assert().isInstanceOf(AuditingAuthorization::class.java)
            }
    }

    @Test
    fun auditOffLoadsNothing() {
        contextRunner
            .withPropertyValues("cosec.audit.enabled=false")
            .withClassLoader(FilteredClassLoader("me.ahoo.cosec.opentelemetry.CoSecInstrumenter"))
            .withUserConfiguration(
                AutoConfiguredAuthorizationTestConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBeansOfType(AuditingAuthorization::class.java).assert().isEmpty()
                context.getBeansOfType(AuditEventSink::class.java).assert().isEmpty()
            }
    }

    @Test
    fun customSinkWins() {
        contextRunner
            .withClassLoader(FilteredClassLoader("me.ahoo.cosec.opentelemetry.CoSecInstrumenter"))
            .withBean("customSink", AuditEventSink::class.java, Supplier { mockk() })
            .withUserConfiguration(
                AutoConfiguredAuthorizationTestConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                val sink = context.getBean(AuditEventSink::class.java)
                (sink is LoggingAuditEventSink).assert().isFalse()
            }
    }

    @Test
    fun skippedWhenAuthorizationBeanReplacedByUser() {
        contextRunner
            .withClassLoader(FilteredClassLoader("me.ahoo.cosec.opentelemetry.CoSecInstrumenter"))
            .withBean("customAuthorization", Authorization::class.java, Supplier { mockk() })
            .withUserConfiguration(CoSecAuditAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.getBeansOfType(AuditingAuthorization::class.java).assert().isEmpty()
            }
    }

    @Test
    fun skippedWhenPrimaryAuthorizationUsesAutoConfiguredBeanName() {
        val customAuthorization = mockk<Authorization>()
        contextRunner
            .withClassLoader(FilteredClassLoader("me.ahoo.cosec.opentelemetry.CoSecInstrumenter"))
            .withBean(
                CoSecAuthorizationAutoConfiguration.BEAN_NAME,
                Authorization::class.java,
                Supplier { customAuthorization },
                { it.isPrimary = true },
            )
            .withUserConfiguration(
                CoSecAuditAutoConfiguration::class.java,
                CoSecAuditFallbackAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBeansOfType(AuditingAuthorization::class.java).assert().isEmpty()
                context.getBean(Authorization::class.java).assert().isSameAs(customAuthorization)
            }
    }

    @Test
    fun auditRemainsPrimaryWhenOpenTelemetryAutoConfigurationIsAbsent() {
        contextRunner
            .withUserConfiguration(
                AutoConfiguredAuthorizationTestConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
                CoSecAuditFallbackAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBeansOfType(AuditingAuthorization::class.java).assert().hasSize(1)
                context.getBean(Authorization::class.java).assert().isInstanceOf(AuditingAuthorization::class.java)
            }
    }
}
