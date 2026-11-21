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

package me.ahoo.cosec.spring.boot.micrometer

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeListenerLane
import me.ahoo.cosec.control.audit.ChangeListeners

/**
 * The change listeners' queues (`CTL-66`), each tagged with its listener: the events and bytes queued, the age of the
 * oldest queued event (a sink that stopped answering shows here first), the BEST_EFFORT events a full queue dropped, and
 * the events of stored steps a REQUIRED listener missed, its queue full or its call failed (alert on any).
 */
@InternalCoSecApi
object ChangeListenerMeters {
    const val QUEUED = "cosec.control.listener.queued"
    const val QUEUED_BYTES = "cosec.control.listener.queued.bytes"
    const val OLDEST_AGE = "cosec.control.listener.oldest.age"
    const val DROPPED = "cosec.control.listener.dropped"
    const val LOST = "cosec.control.listener.lost"
    const val LISTENER_TAG = "listener"

    fun bind(registry: MeterRegistry, listeners: ChangeListeners) {
        listeners.queues.forEach { bind(registry, it) }
    }

    private fun bind(registry: MeterRegistry, queue: ChangeListenerLane) {
        val name = queue.listener.name
        Gauge.builder(QUEUED, queue) { it.depth.toDouble() }
            .tag(LISTENER_TAG, name).description("Change events queued for the listener").register(registry)
        Gauge.builder(QUEUED_BYTES, queue) { it.bytes.toDouble() }
            .tag(LISTENER_TAG, name).baseUnit("bytes")
            .description("What the queued change events hold, of the listener's share of the heap").register(registry)
        Gauge.builder(OLDEST_AGE, queue) { it.oldestAge.inWholeMilliseconds / MILLIS }
            .tag(LISTENER_TAG, name).baseUnit("seconds")
            .description("How long the oldest queued change event has waited; zero when none waits").register(registry)
        FunctionCounter.builder(DROPPED, queue) { it.dropped.toDouble() }
            .tag(LISTENER_TAG, name)
            .description("BEST_EFFORT change events dropped because the listener's queue was full").register(registry)
        FunctionCounter.builder(LOST, queue) { it.lost.toDouble() }
            .tag(LISTENER_TAG, name)
            .description("Change events of stored steps a REQUIRED listener missed: its queue stayed full, or its call failed")
            .register(registry)
    }

    private const val MILLIS = 1000.0
}
