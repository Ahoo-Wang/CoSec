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
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * `cosecControlStoreFormat`: DOWN while the control store is in the format before 8.0.0-beta.5, with a `format` detail
 * naming `cosec-migrate repair-references`; UP otherwise. It reports the [monitor]'s last reading and reads nothing
 * itself. An alert: the data plane still decides every request, so it belongs in no readiness group.
 */
@InternalCoSecApi
class ControlStoreFormatHealthIndicator(private val monitor: ControlStoreFormatMonitor) : HealthIndicator {
    override fun health(): Health {
        val status = monitor.status()
        return (if (status.down) Health.down() else Health.up()).withDetail("format", status.detail).build()
    }
}
