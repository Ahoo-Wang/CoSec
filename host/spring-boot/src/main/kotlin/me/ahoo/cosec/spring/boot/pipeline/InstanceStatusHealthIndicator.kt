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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.shadow.InstanceReporter
import me.ahoo.cosec.spi.shadow.InstanceStatus
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * `cosecInstanceStatus` (`SNP-25`): the status this instance reports, so that the control plane sees it among the readers
 * of the store: its `instance`, its `release`, when the last status was written (`lastWritten`), and `failingSince` with
 * `failedWrites` while writes fail. Always UP: the status is what a publish waits for, never what a request is decided
 * on, so it belongs in no readiness group; a failing write is an alert, read from the details and the log.
 */
@InternalCoSecApi
class InstanceStatusHealthIndicator(private val reporter: InstanceReporter) : HealthIndicator {
    override fun health(): Health {
        val builder = Health.up()
            .withDetail("instance", reporter.instance)
            .withDetail("release", InstanceStatus.RELEASE ?: "unknown")
        reporter.lastWritten?.let { builder.withDetail("lastWritten", it.toString()) }
        reporter.failingSince?.let {
            builder.withDetail("failingSince", it.toString()).withDetail("failedWrites", reporter.failedWrites.get())
        }
        return builder.build()
    }
}
