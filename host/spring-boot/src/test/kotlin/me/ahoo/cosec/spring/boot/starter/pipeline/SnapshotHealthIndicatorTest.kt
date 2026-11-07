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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.Snapshot
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.health.contributor.Status
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration

/** The states of [SnapshotHealthIndicator] and [SnapshotReadiness] the starter test does not reach. */
class SnapshotHealthIndicatorTest {
    private fun source(state: DocumentState<Any>?, prewarmed: Boolean): SnapshotSource<Any, Any> {
        val snapshot = state?.let { policies -> mockk<Snapshot<Any, Any>> { every { this@mockk.policies } returns policies } }
        return mockk {
            every { peek(any()) } returns snapshot
            every { isPrewarmed } returns prewarmed
        }
    }

    private fun health(state: DocumentState<Any>?, prewarmed: Boolean) =
        SnapshotHealthIndicator(source(state, prewarmed)).health()

    @Test
    fun `health names the platform document's state`() {
        health(null, false).apply {
            status.assert().isEqualTo(Status.DOWN)
            details["platform"].assert().isEqualTo("loading")
            details["prewarmed"].assert().isEqualTo(false)
        }
        health(DocumentState.Absent, true).details["platform"].assert().isEqualTo("absent")
        health(DocumentState.Failed(IllegalStateException("unreadable")), true).apply {
            status.assert().isEqualTo(Status.DOWN)
            details["platform"].assert().isEqualTo("failed")
        }
        // Installed, but a prewarmed tenant is still missing.
        health(DocumentState.Present(Any()), false).status.assert().isEqualTo(Status.DOWN)
        health(DocumentState.Present(Any()), true).status.assert().isEqualTo(Status.UP)
    }

    /**
     * RC1-47 (a): Redis's eviction policy is read again at most once every interval. While it may evict the snapshot, or
     * cannot be read, the indicator is DOWN and says why; the change is logged at ERROR once, not on every call.
     */
    @Test
    fun `health is down while Redis may evict the snapshot, read again at most once per interval`() {
        var now = 0L
        var reading: EvictionPolicyCheck.Reading = EvictionPolicyCheck.Reading.Policies(mapOf("node" to "noeviction"))
        var reads = 0
        val read = {
            reads++
            reading
        }
        val monitor = EvictionPolicyMonitor(read, Duration.ofSeconds(10)) { now }
        val indicator = SnapshotHealthIndicator(source(DocumentState.Present(Any()), true), monitor)
        val logger = LoggerFactory.getLogger(EvictionPolicyMonitor::class.java) as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        try {
            indicator.health().status.assert().isEqualTo(Status.UP)
            reading = EvictionPolicyCheck.Reading.Policies(mapOf("node" to "allkeys-lru"))
            // Within the interval the last reading stands.
            indicator.health().status.assert().isEqualTo(Status.UP)
            reads.assert().isEqualTo(1)
            now += Duration.ofSeconds(10).toNanos()
            repeat(2) {
                indicator.health().apply {
                    status.assert().isEqualTo(Status.DOWN)
                    details["eviction"].toString().assert().contains("allkeys-lru")
                    details["platform"].assert().isEqualTo("installed")
                }
            }
            reads.assert().isEqualTo(2)
            now += Duration.ofSeconds(10).toNanos()
            reading = EvictionPolicyCheck.Reading.Unreadable("INFO memory failed or is not allowed")
            indicator.health().status.assert().isEqualTo(Status.DOWN)
            now += Duration.ofSeconds(10).toNanos()
            reading = EvictionPolicyCheck.Reading.Policies(mapOf("node" to "noeviction"))
            indicator.health().apply {
                status.assert().isEqualTo(Status.UP)
                details.containsKey("eviction").assert().isFalse()
            }
            logged.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }.assert().hasSize(2)
                .anyMatch { it.contains("allkeys-lru") }.anyMatch { it.contains("Cannot tell") }
            logged.list.filter { it.level == Level.INFO }.assert().hasSize(1)
        } finally {
            logger.detachAppender(logged)
        }
        // CoCache's in-memory backend evicts nothing behind its back.
        SnapshotHealthIndicator(
            source(DocumentState.Present(Any()), true),
            EvictionPolicyMonitor({ EvictionPolicyCheck.Reading.NotRedis }, Duration.ofSeconds(1)),
        ).health().status.assert().isEqualTo(Status.UP)
    }

    @Test
    fun `a ready snapshot accepts at once, and refusals or its own events are ignored`() {
        val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
        SnapshotReadiness(
            source(DocumentState.Present(Any()), true),
            publisher,
            interval = Duration.ofMillis(10),
        ).use { readiness ->
            readiness.isReady.assert().isTrue()
            readiness.onApplicationEvent(AvailabilityChangeEvent(this, ReadinessState.REFUSING_TRAFFIC))
            readiness.onApplicationEvent(AvailabilityChangeEvent(readiness, ReadinessState.ACCEPTING_TRAFFIC))
            readiness.onApplicationEvent(AvailabilityChangeEvent(this, ReadinessState.ACCEPTING_TRAFFIC))
            Thread.sleep(100)
            // Spring's own acceptance stands: nothing to refuse, nothing to announce.
            confirmVerified(publisher)
        }
    }

    @Test
    fun `nothing is announced before the application has started`() {
        val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
        SnapshotReadiness(source(DocumentState.Present(Any()), true), publisher, interval = Duration.ofMillis(10)).use {
            Thread.sleep(100)
            confirmVerified(publisher)
        }
    }

    /** Spring's acceptance can land after a refusal and overwrite it: the timer refuses again while it stands. */
    @Test
    fun `a refusal Spring overwrote is published again until the snapshot is ready`() {
        val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val availability = mockk<ApplicationAvailability> {
            every { readinessState } returns ReadinessState.ACCEPTING_TRAFFIC
        }
        SnapshotReadiness(source(null, false), publisher, availability, Duration.ofMillis(10)).use { readiness ->
            readiness.onApplicationEvent(AvailabilityChangeEvent(this, ReadinessState.ACCEPTING_TRAFFIC))
            verify(timeout = 5_000, atLeast = 3) {
                publisher.publishEvent(
                    match<ApplicationEvent> {
                        (it as AvailabilityChangeEvent<*>).state == ReadinessState.REFUSING_TRAFFIC
                    }
                )
            }
        }
    }
}
