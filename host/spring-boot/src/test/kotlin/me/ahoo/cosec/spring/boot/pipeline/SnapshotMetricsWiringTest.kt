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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.micrometer.MicrometerSnapshotMetrics
import me.ahoo.cosec.runtime.snapshot.SnapshotMetrics
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.NoTokenStore
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.lazily
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** `OBS-5`: the data plane's snapshot source is metered in the application's registry, and only when metrics are on. */
class SnapshotMetricsWiringTest {
    private val documents = InMemorySnapshotDocumentStore().apply {
        put(
            SnapshotKey.Platform,
            SnapshotDocuments.read(
                """{"schema":2,"revision":1,"publishedAt":"1970-01-01T00:00:00Z","policies":[],"bindings":[]}"""
                    .encodeToByteArray()
            ),
        )
    }

    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("${JwtProperties.PREFIX}.enabled=false", "cosec.authorization.routes.location=")
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })
        .withBean(SnapshotDocumentStore::class.java, { documents })

    private val registry = SimpleMeterRegistry()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun installs(): Double =
        registry.find(MicrometerSnapshotMetrics.INSTALLS).tags("kind", "platform").counter()?.count() ?: 0.0

    @Test
    fun `with a registry the source counts into it and its gauges are bound`() {
        runner.withBean(MeterRegistry::class.java, { registry }).run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(SnapshotMetrics::class.java).keys.assert().containsExactly("cosecSnapshotMetrics")
            // The startup load of the platform document went through the binding the source was built with.
            waitFor { installs() == 1.0 }
            installs().assert().isEqualTo(1.0)
            registry.find(MicrometerSnapshotMetrics.INSTALLED).tags("kind", "platform", "state", "present").gauge()
                .assert().isNotNull()
            registry.find(MicrometerSnapshotMetrics.SUMMARY_AGE).gauge().assert().isNotNull()
        }
    }

    @Test
    fun `under lazy initialization the gauges are bound too`() {
        runner.withBean(MeterRegistry::class.java, { registry }).lazily().run { context ->
            context.startupFailure.assert().isNull()
            registry.find(MicrometerSnapshotMetrics.SUMMARY_AGE).gauge().assert().isNotNull()
        }
    }

    @Test
    fun `without a registry, or with metrics off, the source is not metered`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(SnapshotMetrics::class.java).assert().isEmpty()
        }
        runner.withBean(MeterRegistry::class.java, { registry })
            .withPropertyValues("cosec.metrics.enabled=false")
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(SnapshotMetrics::class.java).assert().isEmpty()
                registry.meters.filter { it.id.name.startsWith("cosec.snapshot.") }.assert().isEmpty()
            }
    }
}
