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

package me.ahoo.cosec.spring.boot.starter.audit.kafka

import io.mockk.mockk
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.audit.LoggingAuditEventSink
import me.ahoo.cosec.kafka.KafkaAuditEventSink
import me.ahoo.cosec.spring.boot.starter.audit.AutoConfiguredAuthorizationTestConfiguration
import me.ahoo.cosec.spring.boot.starter.audit.CoSecAuditAutoConfiguration
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.core.KafkaTemplate
import java.util.function.Supplier

class CoSecKafkaAuditAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CoSecKafkaAuditAutoConfiguration::class.java,
                CoSecAuditAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(AutoConfiguredAuthorizationTestConfiguration::class.java)

    @Test
    fun kafkaSinkWinsByDefault() {
        contextRunner
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
            .run { context ->
                context.getBean(AuditEventSink::class.java).assert().isInstanceOf(KafkaAuditEventSink::class.java)
                context.getBeansOfType(LoggingAuditEventSink::class.java).assert().isEmpty()
            }
    }

    @Test
    fun disabledKafkaFallsBackToLogging() {
        contextRunner
            .withKafkaTemplate()
            .withPropertyValues("cosec.audit.kafka.enabled=false")
            .run { context ->
                context.getBean(AuditEventSink::class.java).assert().isInstanceOf(LoggingAuditEventSink::class.java)
            }
    }

    @Test
    fun missingKafkaFallsBackToLogging() {
        contextRunner.run { context ->
            context.getBean(AuditEventSink::class.java).assert().isInstanceOf(LoggingAuditEventSink::class.java)
        }
    }

    @Test
    fun customSinkWins() {
        val customSink = mockk<AuditEventSink>()
        contextRunner
            .withKafkaTemplate()
            .withBean("customSink", AuditEventSink::class.java, Supplier { customSink })
            .run { context ->
                context.getBean(AuditEventSink::class.java).assert().isSameAs(customSink)
            }
    }

    @Test
    fun auditOffLoadsNoSink() {
        contextRunner
            .withKafkaTemplate()
            .withPropertyValues("cosec.audit.enabled=false")
            .run { context ->
                context.getBeansOfType(AuditEventSink::class.java).assert().isEmpty()
            }
    }

    private fun ApplicationContextRunner.withKafkaTemplate(): ApplicationContextRunner {
        return withBean(
            "kafkaTemplate",
            KafkaTemplate::class.java,
            Supplier { mockk<KafkaTemplate<String, String>>(relaxed = true) },
        )
    }
}
