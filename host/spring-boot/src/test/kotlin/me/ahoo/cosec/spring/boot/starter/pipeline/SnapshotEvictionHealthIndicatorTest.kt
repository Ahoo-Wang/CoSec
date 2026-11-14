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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.MembershipLoader
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.starter.lazily
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * RC1-47 (a), review M3 of #1240: `cosecSnapshotEviction` reports the eviction policy the monitor last read, every
 * interval, on a thread of its own: a probe never reads Redis. A policy that may evict is DOWN in both modes and logged at
 * ERROR when that starts; one that cannot be read is DOWN under `fail` and UP `unknown` under `warn`, logged once.
 */
class SnapshotEvictionHealthIndicatorTest {
    private val unreadable = EvictionPolicyCheck.Reading.Unreadable("INFO memory failed or is not allowed")
    private val evicting = EvictionPolicyCheck.Reading.Policies(mapOf("node" to "allkeys-lru"))
    private val keeping = EvictionPolicyCheck.Reading.Policies(mapOf("node" to EvictionPolicyCheck.NOEVICTION))

    private fun <T> logged(block: (ListAppender<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger(EvictionPolicyMonitor::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            return block(appender)
        } finally {
            logger.detachAppender(appender)
        }
    }

    /** The starter wires both: `cosecSnapshot` for readiness, and `cosecSnapshotEviction` beside it, for alerts. */
    @Test
    fun `the starter wires the eviction indicator beside the snapshot's`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    CoCacheAutoConfiguration::class.java,
                    CoSecTokenRevocationCacheAutoConfiguration::class.java,
                    CoSecJwtAutoConfiguration::class.java,
                    CoSecPipelineIntegrationAutoConfiguration::class.java,
                    CoSecMembershipAutoConfiguration::class.java,
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues(
                "cocache.backend=memory",
                "cosec.authorization.snapshot.eviction-check=warn",
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "cosec.authorization.routes.location=",
            )
            .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
            .withBean(MembershipLoader::class.java, {
                MembershipLoader { _, _ ->
                    MembershipAnswer.Known(
                        Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList())
                    )
                }
            })
            .run { context ->
                context.startupFailure.assert().isNull()
                val indicators = context.getBeansOfType(HealthIndicator::class.java)
                indicators.keys.assert().contains(
                    "cosecSnapshotHealthIndicator",
                    "cosecSnapshotEvictionHealthIndicator"
                )
                // CoCache's in-memory backend evicts nothing behind its back.
                indicators.getValue(
                    "cosecSnapshotEvictionHealthIndicator"
                ).health()!!.status.assert().isEqualTo(Status.UP)
            }
    }

    /**
     * The checks read the backend of the runtime the snapshot store is created on, as CoCache's own check does since
     * 8.0.0-alpha.6 (#874): an application's `CacheRuntime` beside a `CoherenceBackend` bean of another Redis is
     * checked on its own backend. Here the bean is one no check can read, and the runtime is in memory: startup passes
     * under `fail`, which reading the bean would refuse.
     */
    @Test
    fun `the checks read the snapshot runtime's backend, not another backend bean`() {
        val unreadableBean = mockk<CoherenceBackend>(relaxed = true)
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    CoCacheAutoConfiguration::class.java,
                    CoSecTokenRevocationCacheAutoConfiguration::class.java,
                    CoSecJwtAutoConfiguration::class.java,
                    CoSecPipelineIntegrationAutoConfiguration::class.java,
                    CoSecMembershipAutoConfiguration::class.java,
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "cosec.authorization.routes.location=",
            )
            .withBean(CoherenceBackend::class.java, { unreadableBean })
            .withBean(CacheRuntime::class.java, { CacheRuntime.builder().backend(InMemoryBackend()).build() })
            .withBean(MembershipLoader::class.java, {
                MembershipLoader { _, _ ->
                    MembershipAnswer.Known(Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
                }
            })
            .run { context ->
                context.startupFailure.assert().isNull()
                val monitor = context.getBean(EvictionPolicyMonitor::class.java)
                monitor.refresh()
                monitor.status().down.assert().isFalse()
                monitor.status().detail.assert().isNull()
            }
    }

    @Test
    fun `a probe reads only what the monitor last read`() {
        val reads = AtomicInteger()
        var reading: EvictionPolicyCheck.Reading = keeping
        val read = {
            reads.incrementAndGet()
            reading
        }
        val monitor = EvictionPolicyMonitor(read, Duration.ofHours(1))
        val indicator = SnapshotEvictionHealthIndicator(monitor)
        // Before the first reading: startup's check has passed.
        indicator.health().apply {
            status.assert().isEqualTo(Status.UP)
            details["eviction"].assert().isEqualTo("pending")
        }
        monitor.refresh()
        reading = evicting
        repeat(3) { indicator.health().status.assert().isEqualTo(Status.UP) }
        reads.get().assert().isEqualTo(1)
        monitor.refresh()
        indicator.health().apply {
            status.assert().isEqualTo(Status.DOWN)
            details["eviction"].toString().assert().contains("allkeys-lru")
        }
        reads.get().assert().isEqualTo(2)
    }

    @Test
    fun `under fail a policy that may evict or cannot be read is down, logged when it starts`() {
        var reading: EvictionPolicyCheck.Reading = keeping
        val monitor =
            EvictionPolicyMonitor({ reading }, Duration.ofHours(1), RuntimeAuthorizationProperties.EvictionCheck.FAIL)
        val indicator = SnapshotEvictionHealthIndicator(monitor)
        logged { appender ->
            monitor.refresh()
            indicator.health().apply {
                status.assert().isEqualTo(Status.UP)
                details.containsKey("eviction").assert().isFalse()
            }
            reading = evicting
            repeat(2) { monitor.refresh() }
            indicator.health().status.assert().isEqualTo(Status.DOWN)
            reading = unreadable
            monitor.refresh()
            indicator.health().apply {
                status.assert().isEqualTo(Status.DOWN)
                details["eviction"].toString().assert().contains("Cannot tell")
            }
            // No node to ask reads nothing either (review L3 of #1240).
            reading = EvictionPolicyCheck.Reading.Policies(emptyMap())
            monitor.refresh()
            indicator.health().status.assert().isEqualTo(Status.DOWN)
            reading = keeping
            monitor.refresh()
            indicator.health().status.assert().isEqualTo(Status.UP)
            appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }.assert().hasSize(3)
            appender.list.filter { it.level == Level.INFO }.assert().hasSize(1)
        }
    }

    @Test
    fun `under warn a policy that cannot be read is up and unknown, one that may evict is down`() {
        var reading: EvictionPolicyCheck.Reading = unreadable
        val monitor =
            EvictionPolicyMonitor({ reading }, Duration.ofHours(1), RuntimeAuthorizationProperties.EvictionCheck.WARN)
        val indicator = SnapshotEvictionHealthIndicator(monitor)
        logged { appender ->
            repeat(2) {
                monitor.refresh()
                indicator.health().apply {
                    status.assert().isEqualTo(Status.UP)
                    details["eviction"].assert().isEqualTo("unknown")
                }
            }
            reading = EvictionPolicyCheck.Reading.Policies(emptyMap())
            monitor.refresh()
            indicator.health().status.assert().isEqualTo(Status.UP)
            reading = evicting
            monitor.refresh()
            indicator.health().apply {
                status.assert().isEqualTo(Status.DOWN)
                details["eviction"].toString().assert().contains("allkeys-lru")
            }
            appender.list.filter { it.level == Level.WARN }.assert().hasSize(1)
            appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }.assert().hasSize(1)
                .allMatch { it.contains("allkeys-lru") }
        }
    }

    @Test
    fun `the monitor reads every interval on its own thread, and a read that throws is unreadable`() {
        val reads = AtomicInteger()
        EvictionPolicyMonitor(
            {
                if (reads.incrementAndGet() > 1) throw IllegalStateException("connection reset")
                keeping
            },
            Duration.ofMillis(10),
        ).start().use { monitor ->
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (reads.get() < 3) {
                check(System.nanoTime() < deadline) { "The monitor did not read every interval." }
                Thread.sleep(10)
            }
            monitor.status().down.assert().isTrue()
            monitor.status().detail.toString().assert().contains("connection reset")
        }
        val stopped = reads.get()
        Thread.sleep(100)
        reads.get().assert().isLessThanOrEqualTo(stopped + 1)
    }

    /** A backend no check can see into: the snapshot runtime's own here, so SNP-3 cannot tell whether Redis evicts. */
    private class Opaque(backend: CoherenceBackend = InMemoryBackend()) : CoherenceBackend by backend {
        override fun <T : Any> unwrap(type: Class<T>): T? = null
    }

    private fun opaque(vararg properties: String) = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecMembershipAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
            *properties,
        )
        .withBean(CacheRuntime::class.java, { CacheRuntime.builder().backend(Opaque()).build() })

    /** `STA-3`: SNP-3 runs under lazy initialization, where nothing asks for its check. */
    @Test
    fun `an eviction policy that cannot be read fails startup, lazily too`() {
        for (runner in listOf(opaque(), opaque().lazily())) {
            runner.run { context ->
                val messages = generateSequence(
                    context.startupFailure
                ) { it.cause }.mapNotNull { it.message }.joinToString()
                messages.assert().contains("Cannot tell whether Redis may evict CoSec's policy snapshot")
            }
        }
    }

    /**
     * `STA-3`: under lazy initialization the monitor still reads from startup on, not from the first health probe that
     * would create it: a Redis that starts evicting is logged although no one asks.
     */
    @Test
    fun `the monitor runs from startup under lazy initialization`() {
        opaque("cosec.authorization.snapshot.eviction-check=warn").lazily().run { context ->
            context.startupFailure.assert().isNull()
            context.beanFactory.containsSingleton("cosecEvictionPolicyMonitor").assert().isTrue()
            context.beanFactory.containsSingleton("cosecSnapshotEvictionHealthIndicator").assert().isFalse()
        }
    }
}
