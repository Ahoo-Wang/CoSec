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

package me.ahoo.cosec.spring.boot.starter.control

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.control.store.ControlMaintenance
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class ControlMaintenanceSchedulerTest {
    @Test
    fun `both passes run at their interval, and a failed pass does not stop the next`() {
        val reclaims = AtomicInteger()
        val shadows = AtomicInteger()
        val maintenance = mockk<ControlMaintenance> {
            coEvery { reclaim() } answers {
                if (reclaims.incrementAndGet() == 1) error("Redis is down")
                emptyMap()
            }
            coEvery { closeShadowWindows() } answers {
                shadows.incrementAndGet()
                emptyList()
            }
        }
        val scheduler = ControlMaintenanceScheduler(maintenance, Duration.ofMillis(20), Duration.ofMillis(20))
        scheduler.start()
        scheduler.isRunning.assert().isTrue()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while ((reclaims.get() < 3 || shadows.get() < 3) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        scheduler.stop()
        scheduler.isRunning.assert().isFalse()
        reclaims.get().assert().isGreaterThanOrEqualTo(3)
        shadows.get().assert().isGreaterThanOrEqualTo(3)
    }

    /** Coordinator review: a pass's own timeout, or an Error, must not end the loop silently. */
    @Test
    fun `a pass that times out or throws an error does not stop the next`() {
        val timeout: Throwable = try {
            runBlocking { withTimeout(1) { delay(1_000) } }
            error("unreachable")
        } catch (e: TimeoutCancellationException) {
            e
        }
        val shadows = AtomicInteger()
        val maintenance = mockk<ControlMaintenance> {
            coEvery { reclaim() } returns emptyMap()
            coEvery { closeShadowWindows() } answers {
                when (shadows.incrementAndGet()) {
                    1 -> throw timeout
                    2 -> throw AssertionError("an error")
                    else -> emptyList()
                }
            }
        }
        val scheduler = ControlMaintenanceScheduler(maintenance, Duration.ofHours(1), Duration.ofMillis(20))
        scheduler.start()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (shadows.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        scheduler.stop()
        shadows.get().assert().isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `intervals must be positive`() {
        val maintenance = mockk<ControlMaintenance>()
        assertThrows<IllegalArgumentException> {
            ControlMaintenanceScheduler(maintenance, Duration.ZERO, Duration.ofMinutes(1))
        }
        assertThrows<IllegalArgumentException> {
            ControlMaintenanceScheduler(maintenance, Duration.ofHours(1), Duration.ZERO)
        }
    }
}
