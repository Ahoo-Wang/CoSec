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

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * `cosecSnapshotEviction` (RC1-47 (a), review M3 of #1240): DOWN while Redis may evict the policy snapshot, with an
 * `eviction` detail that says why; UP otherwise, with `eviction: unknown` when the policy cannot be read under
 * `eviction-check: warn`. It reports the [monitor]'s last reading and reads nothing itself, so a probe never waits on
 * Redis. An alert: requests are still decided, so it belongs in no readiness group, where one change to Redis would take
 * every instance out of rotation at once.
 */
@InternalCoSecApi
class SnapshotEvictionHealthIndicator(private val monitor: EvictionPolicyMonitor) : HealthIndicator {
    override fun health(): Health {
        val status = monitor.status()
        val builder = if (status.down) Health.down() else Health.up()
        status.detail?.let { builder.withDetail("eviction", it) }
        return builder.build()
    }
}
