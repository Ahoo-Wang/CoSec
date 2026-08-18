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
import me.ahoo.cosec.opentelemetry.OpenTelemetryRequestAttributesAppender
import me.ahoo.cosec.opentelemetry.TracingAuthorization
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.opentelemetry.CoSecOpenTelemetryAutoConfiguration
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class AuditOpenTelemetryCompositionTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun auditOnAndOtelOnTracingWrapsAuditing() {
        contextRunner
            .withUserConfiguration(
                AutoConfiguredAuthorizationTestConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
                CoSecOpenTelemetryAutoConfiguration::class.java,
                CoSecAuditFallbackAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBeansOfType(AuditingAuthorization::class.java).assert().hasSize(1)
                context.getBeansOfType(TracingAuthorization::class.java).assert().hasSize(1)
                context.getBeansOfType(OpenTelemetryRequestAttributesAppender::class.java).assert().hasSize(1)
                val authorization = context.getBean(Authorization::class.java)
                authorization.assert().isInstanceOf(TracingAuthorization::class.java)
                context.getBean(
                    CoSecOpenTelemetryAutoConfiguration.TRACING_AUTHORIZATION_BEAN_NAME,
                    Authorization::class.java,
                ).assert().isSameAs(authorization)
                val tracing = authorization as TracingAuthorization
                tracing.delegate.assert().isInstanceOf(AuditingAuthorization::class.java)
                val auditing = tracing.delegate as AuditingAuthorization
                auditing.delegate.assert().isSameAs(
                    context.getBean(CoSecAuthorizationAutoConfiguration.BEAN_NAME, Authorization::class.java)
                )
            }
    }

    @Test
    fun auditOffAndOtelOnKeepsCurrentBehavior() {
        contextRunner
            .withPropertyValues("cosec.audit.enabled=false")
            .withUserConfiguration(
                AutoConfiguredAuthorizationTestConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
                CoSecOpenTelemetryAutoConfiguration::class.java,
                CoSecAuditFallbackAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBeansOfType(AuditingAuthorization::class.java).assert().isEmpty()
                context.getBeansOfType(TracingAuthorization::class.java).assert().hasSize(1)
                context.getBeansOfType(OpenTelemetryRequestAttributesAppender::class.java).assert().isEmpty()
                context.getBean(Authorization::class.java).assert().isInstanceOf(TracingAuthorization::class.java)
            }
    }

    @Test
    fun auditOnAndOtelOffAuditingIsPrimary() {
        contextRunner
            .withClassLoader(FilteredClassLoader("me.ahoo.cosec.opentelemetry.CoSecInstrumenter"))
            .withUserConfiguration(
                AutoConfiguredAuthorizationTestConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
                CoSecAuditFallbackAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBean(Authorization::class.java).assert().isInstanceOf(AuditingAuthorization::class.java)
            }
    }

    @Test
    fun customAuditingAuthorizationIsTracedWithoutInternalBeanName() {
        val customAuthorization = AuditingAuthorization(mockk(), AuditEventSink { })
        contextRunner
            .withBean(
                "customAuthorization",
                AuditingAuthorization::class.java,
                Supplier { customAuthorization },
            )
            .withUserConfiguration(
                CoSecAuditAutoConfiguration::class.java,
                CoSecOpenTelemetryAutoConfiguration::class.java,
                CoSecAuditFallbackAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                val authorization = context.getBean(Authorization::class.java)
                authorization.assert().isInstanceOf(TracingAuthorization::class.java)
                (authorization as TracingAuthorization).delegate.assert().isSameAs(customAuthorization)
            }
    }
}
