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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.starter.pipeline

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.micrometer.MetricsListener
import me.ahoo.cosec.spring.boot.starter.lazily
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * #1242: the metrics listener is registered whichever way the application gets its [MeterRegistry]: from Spring Boot's
 * own auto-configuration, which must have run before CoSec asks for one (`@ConditionalOnBean` is decided once), or from a
 * bean of its own.
 */
class MetricsRegistryOrderTest {
    private val runner = ApplicationContextRunner()
        .withPropertyValues("cosec.ip2region.enabled=false")

    private val boot = AutoConfigurations.of(
        CoSecPipelineIntegrationAutoConfiguration::class.java,
        // Listed after CoSec's on purpose: the order is the auto-configurations' to decide, not the list's.
        MetricsAutoConfiguration::class.java,
        CompositeMeterRegistryAutoConfiguration::class.java,
        SimpleMetricsExportAutoConfiguration::class.java,
    )

    @Test
    fun `the registry Spring Boot creates gets the metrics listener`() {
        runner.withConfiguration(boot).run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(MeterRegistry::class.java).assert().hasSize(1)
            context.getBeansOfType(MetricsListener::class.java).assert().hasSize(1)
        }
    }

    @Test
    fun `an application's own registry gets it too`() {
        runner
            .withConfiguration(AutoConfigurations.of(CoSecPipelineIntegrationAutoConfiguration::class.java))
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(MetricsListener::class.java).assert().hasSize(1)
            }
    }

    @Test
    fun `cosec metrics enabled false registers none`() {
        runner.withConfiguration(boot).withPropertyValues("cosec.metrics.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(MeterRegistry::class.java).assert().hasSize(1)
            context.getBeansOfType(MetricsListener::class.java).assert().isEmpty()
        }
    }

    /** `STA-3`: the listeners' counters are bound at startup under lazy initialization too, though nothing asks for them. */
    @Test
    fun `the listeners' counters are bound under lazy initialization`() {
        runner.withConfiguration(boot).lazily().run { context ->
            context.startupFailure.assert().isNull()
            context.beanFactory.containsSingleton("cosecListenerMetrics").assert().isTrue()
        }
    }
}
