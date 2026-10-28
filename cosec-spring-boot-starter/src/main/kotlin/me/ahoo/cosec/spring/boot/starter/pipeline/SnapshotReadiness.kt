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

import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.SnapshotSource
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Readiness follows the snapshot (RFC 0003 §4.3, X6): until the platform document and the prewarmed apps and tenants
 * are installed, every request is a 503, so the instance refuses traffic. Spring marks the application ready when it
 * has started; this turns that back to [ReadinessState.REFUSING_TRAFFIC] while the snapshot is not ready, and to
 * [ReadinessState.ACCEPTING_TRAFFIC] once it is. Liveness is not touched: a missing document is not a reason to restart.
 */
class SnapshotReadiness(
    private val source: SnapshotSource<*, *>,
    private val publisher: ApplicationEventPublisher,
    /**
     * The state Spring holds. Listeners of one event run in an order this cannot choose, so a refusal can land before
     * the acceptance it answers and be overwritten by it: each check refuses again while Spring says accepting and the
     * snapshot is not ready. Without it, the refusal is published once.
     */
    private val availability: ApplicationAvailability? = null,
    interval: Duration = Duration.ofMillis(200),
) : ApplicationListener<AvailabilityChangeEvent<ReadinessState>>, AutoCloseable {
    @Volatile
    private var started = false

    @Volatile
    private var accepting = false

    private val timer = Executors.newSingleThreadScheduledExecutor {
        Thread(
            it,
            "cosec-snapshot-readiness"
        ).apply { isDaemon = true }
    }

    init {
        timer.scheduleWithFixedDelay(::check, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** The platform document is installed, and every prewarmed document. */
    val isReady: Boolean get() = source.isPrewarmed && source.peek()?.policies is DocumentState.Present<*>

    override fun onApplicationEvent(event: AvailabilityChangeEvent<ReadinessState>) {
        if (event.source === this || event.state != ReadinessState.ACCEPTING_TRAFFIC) {
            return
        }
        if (isReady) {
            // Accepting before started: the timer, seeing started, must also see that Spring's acceptance stands, or it
            // announces it a second time.
            accepting = true
            started = true
        } else {
            started = true
            timer.execute(::refuse)
        }
    }

    private fun check() {
        if (accepting) {
            timer.shutdown()
            return
        }
        if (!started) {
            return
        }
        if (isReady) {
            accepting = true
            AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC)
        } else if (availability?.readinessState == ReadinessState.ACCEPTING_TRAFFIC) {
            refuse()
        }
    }

    private fun refuse() {
        if (!accepting) {
            AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC)
        }
    }

    override fun close() {
        timer.shutdownNow()
    }
}
