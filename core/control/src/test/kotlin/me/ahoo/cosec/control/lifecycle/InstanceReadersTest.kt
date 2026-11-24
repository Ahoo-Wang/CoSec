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

package me.ahoo.cosec.control.lifecycle

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.captured
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * `SNP-25`, `CTL-89`: the readers of the store are every instance with a status in the last hour, live or not, each with
 * its release (`unknown` for one that does not name it); a mixed deployment is said once each time its releases change.
 */
class InstanceReadersTest {
    private val clock = MutableClock()
    private val written = InMemoryShadowReports(clock)
    private val windows = ShadowWindows(written, clock = clock)

    @Test
    fun `the readers are the statuses of the last hour, with their releases`() = runBlocking<Unit> {
        written.write(InstanceStatus("old", clock.instant(), release = null))
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        written.write(InstanceStatus("gw-1", clock.instant(), release = "8.0.0-beta.9"))
        written.write(InstanceStatus("gw-2", clock.instant(), release = "8.0.0-beta.9"))
        val readers = windows.readers()
        readers.statuses.map { it.instance }.assert().containsExactlyInAnyOrder("old", "gw-1", "gw-2")
        readers.releases.assert().isEqualTo(mapOf("8.0.0-beta.9" to 2, "unknown" to 1))
        // Live is a minute: the old one is a reader, not a live instance.
        windows.acknowledgement("w").waitingFor.assert().containsExactlyInAnyOrder("gw-1", "gw-2")
        clock.now = clock.now.plus(Duration.ofMinutes(55))
        windows.readers().statuses.map { it.instance }.assert().containsExactlyInAnyOrder("gw-1", "gw-2")
    }

    @Test
    fun `a mixed deployment is said once each time its releases change`() = runBlocking<Unit> {
        val logger = LoggerFactory.getLogger(InstanceStatuses::class.java) as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        try {
            written.write(InstanceStatus("gw-1", clock.instant(), release = "8.0.0-beta.9"))
            windows.readers()
            written.write(InstanceStatus("gw-2", clock.instant(), release = "8.0.0-beta.8"))
            clock.now = clock.now.plus(InstanceStatuses.MAX_AGE).plusMillis(1)
            windows.readers()
            clock.now = clock.now.plus(InstanceStatuses.MAX_AGE).plusMillis(1)
            windows.readers()
            val said = logged.captured().map { it.formattedMessage }.filter {
                it.contains(
                    "release(s) read this store"
                )
            }
            said.assert().hasSize(1)
            said.single().assert().contains("8.0.0-beta.8 ×1").contains("8.0.0-beta.9 ×1").contains("SNP-25")
        } finally {
            logger.detachAppender(logged)
        }
    }
}
