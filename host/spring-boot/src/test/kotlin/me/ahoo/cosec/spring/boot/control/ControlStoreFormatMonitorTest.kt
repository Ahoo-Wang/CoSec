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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.time.Duration

/**
 * A control store written before 8.0.0-beta.5 is reported, never rewritten: DOWN with a `format` detail naming
 * `cosec-migrate repair-references` while the old document list is there, UP once it is gone, and unknown, not down,
 * when it cannot be read.
 */
class ControlStoreFormatMonitorTest {
    private var unrepaired: Boolean? = true
    private val monitor = ControlStoreFormatMonitor({ unrepaired ?: error("Redis is down") }, Duration.ofMinutes(1))
    private val health = ControlStoreFormatHealthIndicator(monitor)

    @Test
    fun `a store in the old format is down, naming the repair, until the repair has run`() {
        health.health().status.assert().isEqualTo(Status.UP)
        monitor.refresh()
        health.health().let {
            it.status.assert().isEqualTo(Status.DOWN)
            it.details["format"].toString().assert().contains("cosec-migrate repair-references")
        }
        unrepaired = null
        monitor.refresh()
        health.health().let {
            it.status.assert().isEqualTo(Status.UP)
            it.details["format"].assert().isEqualTo("unknown")
        }
        unrepaired = false
        monitor.refresh()
        health.health().details["format"].assert().isEqualTo("8.0.0-beta.5")
    }
}
