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

package me.ahoo.cosec.spring.boot.pipeline

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.micrometer.MetricsListener
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.identity.IdentityRefusals
import me.ahoo.cosec.spring.boot.lazily
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext

/**
 * `OBS-7`: a service in gateway mode counts every forwarded identity it refuses in `cosec.identity.refused`, by problem;
 * without `cosec-micrometer` or a registry the count is kept, the reasons logged, and nothing fails.
 */
class GatewayModeMetricsTest {
    private val runner = ApplicationContextRunner()
        .withPropertyValues("cosec.pipeline.mode=gateway")
        .withConfiguration(
            AutoConfigurations.of(
                CoSecGatewayModeAutoConfiguration::class.java,
                // Listed after CoSec's on purpose: the order is the auto-configurations' to decide, not the list's.
                MetricsAutoConfiguration::class.java,
                CompositeMeterRegistryAutoConfiguration::class.java,
                SimpleMetricsExportAutoConfiguration::class.java,
            )
        )

    private fun refuse(context: ApplicationContext) = runBlocking {
        context.getBean(IdentityGate::class.java).evaluate(
            HttpFacts(
                "GET",
                "/orders",
                null,
                Headers.of(mapOf(IdentityCodec.HEADER to listOf("not base64!"))),
                Cookies.EMPTY,
                "10.0.0.1",
                HostScope(null),
            )
        )
    }

    private fun refused(registry: MeterRegistry): Map<String?, Double> =
        registry.find(MetricsListener.IDENTITY_REFUSED).functionCounters()
            .associate { it.id.getTag(MetricsListener.PROBLEM_TAG) to it.count() }

    @Test
    fun `the gate's refusals are counted in the registry by problem`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            refuse(context)
            refused(context.getBean(MeterRegistry::class.java)).assert().isEqualTo(
                mapOf(
                    ProblemType.INVALID_REQUEST.slug to 1.0,
                    ProblemType.TOKEN_INVALID.slug to 0.0,
                    ProblemType.TOKEN_EXPIRED.slug to 0.0,
                )
            )
        }
    }

    /** `STA-3`: nothing asks for the meter, so it is bound at startup under lazy initialization too. */
    @Test
    fun `the meter is bound under lazy initialization`() {
        runner.lazily().run { context ->
            context.startupFailure.assert().isNull()
            refused(context.getBean(MeterRegistry::class.java)).keys.assert().hasSize(IdentityRefusals.TYPES.size)
        }
    }

    @Test
    fun `cosec metrics enabled false registers no meter, and the gate still counts`() {
        runner.withPropertyValues("cosec.metrics.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            refuse(context)
            refused(context.getBean(MeterRegistry::class.java)).assert().isEmpty()
            context.getBean(IdentityRefusals::class.java).count(ProblemType.INVALID_REQUEST).assert().isEqualTo(1L)
        }
    }

    @Test
    fun `without cosec-micrometer or a registry the gate still counts, and nothing fails`() {
        val noMicrometer = runner.withClassLoader(FilteredClassLoader("me.ahoo.cosec.micrometer"))
        val noRegistry = ApplicationContextRunner()
            .withPropertyValues("cosec.pipeline.mode=gateway")
            .withConfiguration(AutoConfigurations.of(CoSecGatewayModeAutoConfiguration::class.java))
        listOf(noMicrometer, noRegistry).forEach { variant ->
            variant.run { context ->
                context.startupFailure.assert().isNull()
                refuse(context).status.assert().isEqualTo(ProblemType.INVALID_REQUEST.status)
                context.getBean(IdentityRefusals::class.java).count(ProblemType.INVALID_REQUEST).assert().isEqualTo(1L)
            }
        }
    }
}
