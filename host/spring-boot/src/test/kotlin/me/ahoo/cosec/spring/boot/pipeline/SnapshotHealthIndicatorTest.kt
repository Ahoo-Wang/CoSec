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

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.Snapshot
import me.ahoo.cosec.runtime.snapshot.SnapshotEntry
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration

/** The states of [SnapshotHealthIndicator] and [SnapshotReadiness] the starter test does not reach. */
class SnapshotHealthIndicatorTest {
    private fun source(
        state: DocumentState<Any>?,
        prewarmed: Boolean,
        tenant: DocumentState<Any>? = null,
    ): SnapshotSource<Any, Any> {
        val snapshot = state?.let { policies -> mockk<Snapshot<Any, Any>> { every { this@mockk.policies } returns policies } }
        val entries = listOfNotNull(
            tenant?.let { tenantState ->
                mockk<SnapshotEntry<Any>> {
                    every { key } returns SnapshotKey.Tenant(TENANT)
                    every { this@mockk.state } returns tenantState
                }
            }
        )
        return mockk {
            every { peek(any()) } returns snapshot
            every { isPrewarmed } returns prewarmed
            every { entries() } returns entries
        }
    }

    /** Readiness over [source], with one prewarmed tenant that has no entry until the prewarm completes. */
    private fun readiness(source: SnapshotSource<Any, Any>) = DocumentReadiness(
        source,
        listOf(SnapshotKey.Tenant(TENANT))
    )

    private fun readiness(state: DocumentState<Any>?, prewarmed: Boolean) = readiness(source(state, prewarmed))

    private fun health(state: DocumentState<Any>?, prewarmed: Boolean): Health {
        val source = source(state, prewarmed)
        return SnapshotHealthIndicator(source, readiness(source)).health()
    }

    @Test
    fun `health names the platform document's state`() {
        health(null, false).apply {
            status.assert().isEqualTo(Status.DOWN)
            details["platform"].assert().isEqualTo("loading")
            details["prewarmed"].assert().isEqualTo(false)
            details["missing"].assert().isEqualTo(listOf("platform", "tenant:$TENANT"))
        }
        health(DocumentState.Absent, true).details["platform"].assert().isEqualTo("absent")
        health(DocumentState.Failed(IllegalStateException("unreadable")), true).apply {
            status.assert().isEqualTo(Status.DOWN)
            details["platform"].assert().isEqualTo("failed")
        }
        // Installed, but a prewarmed tenant is still missing.
        health(DocumentState.Present(Any()), false).apply {
            status.assert().isEqualTo(Status.DOWN)
            details["missing"].assert().isEqualTo(listOf("tenant:$TENANT"))
        }
        health(DocumentState.Present(Any()), true).apply {
            status.assert().isEqualTo(Status.UP)
            details["missing"].assert().isEqualTo(emptyList<String>())
        }
    }

    /**
     * Where readiness no longer waits for the prewarm's future (it was `isPrewarmed && platform present`): once each
     * prewarmed document has an entry that is not failed, the instance is ready, before the prewarm attempt completes.
     */
    @Test
    fun `every prewarmed document installed is ready before the prewarm completes, a failed one is not`() {
        val present = DocumentState.Present(Any())
        readiness(source(present, prewarmed = false, tenant = present)).missing().assert().isEmpty()
        readiness(source(present, prewarmed = false, tenant = DocumentState.Absent)).missing().assert().isEmpty()
        readiness(source(present, prewarmed = false, tenant = DocumentState.Failed(IllegalStateException("down"))))
            .missing().assert().containsExactly(SnapshotKey.Tenant(TENANT))
    }

    @Test
    fun `a ready snapshot accepts at once, and refusals or its own events are ignored`() {
        val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
        SnapshotReadiness(
            readiness(DocumentState.Present(Any()), true),
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
        SnapshotReadiness(
            readiness(DocumentState.Present(Any()), true),
            publisher,
            interval = Duration.ofMillis(10)
        ).use {
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
        SnapshotReadiness(readiness(null, false), publisher, availability, Duration.ofMillis(10)).use { readiness ->
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

    private companion object {
        const val TENANT = "t1"
    }
}
