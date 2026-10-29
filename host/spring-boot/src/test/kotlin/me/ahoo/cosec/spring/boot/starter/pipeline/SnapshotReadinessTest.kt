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

package me.ahoo.cosec.spring.boot.starter.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

/** RFC 0003 X6 and RFC 0006 X8: readiness follows the snapshot, and listeners drain on shutdown. */
class SnapshotReadinessTest {
    private val documents = InMemorySnapshotDocumentStore()

    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                ApplicationAvailabilityAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("${JwtProperties.PREFIX}.enabled=false", "cosec.authorization.routes.location=")
        .withBean(TokenStore::class.java, { TokenStore.NoOp })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })
        .withBean(SnapshotDocumentStore::class.java, { documents })

    private fun status(context: ApplicationContext): Int = runBlocking {
        context.getBean(Pipeline::class.java).evaluate(
            HttpFacts("GET", "/orders", null, Headers.EMPTY, Cookies.EMPTY, "10.0.0.1", HostScope(null))
        ).status
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
    }

    @Test
    fun `readiness refuses traffic until the platform document is installed, liveness is untouched`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val availability = context.getBean(ApplicationAvailability::class.java)
            val health = context.getBean(SnapshotHealthIndicator::class.java)
            // The application has started: Spring says ready.
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC)
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT)
            // No platform document: every request is a 503, and the instance refuses traffic.
            status(context).assert().isEqualTo(503)
            waitFor { availability.readinessState == ReadinessState.REFUSING_TRAFFIC }
            availability.readinessState.assert().isEqualTo(ReadinessState.REFUSING_TRAFFIC)
            availability.livenessState.assert().isEqualTo(LivenessState.CORRECT)
            health.health().status.assert().isEqualTo(Status.DOWN)

            documents.put(
                SnapshotKey.Platform,
                SnapshotDocument(
                    SnapshotDocument.DOCUMENT_SCHEMA,
                    1,
                    Instant.EPOCH,
                    """{"schema":2,"revision":1,"publishedAt":"1970-01-01T00:00:00Z","policies":[],"bindings":[]}"""
                        .encodeToByteArray(),
                ),
            )
            waitFor { availability.readinessState == ReadinessState.ACCEPTING_TRAFFIC }
            availability.readinessState.assert().isEqualTo(ReadinessState.ACCEPTING_TRAFFIC)
            health.health().status.assert().isEqualTo(Status.UP)
            // Decided now: nothing permits an anonymous request.
            status(context).assert().isEqualTo(401)
        }
    }

    @Test
    fun `listener queues and drain take their properties`() {
        runner.withPropertyValues(
            "cosec.observability.listener.queue-capacity=16",
            "cosec.observability.listener.drain-timeout=2s",
        ).run { context ->
            val listeners = context.getBean(DecisionListeners::class.java)
            ReflectionTestUtils.getField(listeners, "drainTimeout").assert().isNotNull()
            context.getBean(ListenerProperties::class.java).queueCapacity.assert().isEqualTo(16)
        }
    }
}
