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

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.micrometer.ChangeListenerMeters
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The control plane's meters, with Micrometer and a `MeterRegistry` ([CoSecControlAutoConfiguration] imports it). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry::class)
internal class ControlMetersConfiguration {
    /** The change listeners' queues (`CTL-66`): bound once every singleton exists. */
    @Bean
    fun cosecChangeListenerMeters(
        registry: ObjectProvider<MeterRegistry>,
        listeners: ObjectProvider<ChangeListeners>,
    ): StartupTask = StartupTask.AfterSingletons {
        val meters = registry.ifAvailable ?: return@AfterSingletons
        listeners.ifAvailable?.let { ChangeListenerMeters.bind(meters, it) }
    }

    /** Instance statuses that did not decode (`CTL-73`): each holds every shadow window until it reads or expires. */
    @Bean
    fun cosecUnreadableStatusMeter(
        registry: ObjectProvider<MeterRegistry>,
        changes: ObjectProvider<Changes>,
    ): StartupTask = StartupTask.AfterSingletons {
        val meters = registry.ifAvailable ?: return@AfterSingletons
        changes.ifAvailable?.let { bound ->
            FunctionCounter.builder(UNREADABLE_STATUSES, bound) { it.unreadableStatuses.toDouble() }
                .description("Instance statuses the control plane read that did not decode: alert on any")
                .register(meters)
        }
    }

    /**
     * What the documents retain (`CTL-84`): set by each reclamation pass; only the instance that held the pass's lease
     * reports what it found, the others 0, so summed across instances they are the store's as of the last pass.
     */
    @Bean
    fun cosecRetainedMeters(
        registry: ObjectProvider<MeterRegistry>,
        maintenance: ObjectProvider<ControlMaintenance>,
        revisions: ObjectProvider<Revisions>,
    ): StartupTask = StartupTask.AfterSingletons {
        val meters = registry.ifAvailable ?: return@AfterSingletons
        maintenance.ifAvailable?.let { bound ->
            Gauge.builder(RECORDS, bound) { it.retained.records.toDouble() }
                .description(
                    "Revision records the documents retain, as the last reclamation pass this instance led found"
                )
                .register(meters)
            Gauge.builder(BYTES, bound) { it.retained.bytes.toDouble() }
                .description("Stored bytes of those records").baseUnit("bytes").register(meters)
            Gauge.builder(OPEN_CHANGES, bound) { it.retained.openChanges.toDouble() }
                .description("Open changes, as the last reclamation pass this instance led found")
                .register(meters)
        }
        revisions.ifAvailable?.let { bound ->
            FunctionCounter.builder(RECLAIMED_YOUNG, bound) { it.reclaimedYoung.toDouble() }
                .description("Records reclaimed younger than min-age, past what a document retains: alert on any")
                .register(meters)
        }
    }

    companion object {
        const val UNREADABLE_STATUSES = "cosec.control.shadow.status.unreadable"
        const val RECORDS = "cosec.control.revisions.records"
        const val BYTES = "cosec.control.revisions.bytes"
        const val RECLAIMED_YOUNG = "cosec.control.revisions.reclaimed-young"
        const val OPEN_CHANGES = "cosec.control.changes.open"
    }
}
