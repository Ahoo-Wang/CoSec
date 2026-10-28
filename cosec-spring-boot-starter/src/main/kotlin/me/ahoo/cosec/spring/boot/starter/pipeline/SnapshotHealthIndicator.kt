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
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * `cosecSnapshot`: UP once the platform document and the prewarmed apps and tenants are installed ([SnapshotReadiness]
 * gates readiness on the same), DOWN before, with what is missing. Add it to the readiness group for its details.
 */
class SnapshotHealthIndicator(private val source: SnapshotSource<*, *>) : HealthIndicator {
    override fun health(): Health {
        val platform = when (source.peek()?.policies) {
            is DocumentState.Present<*> -> "installed"
            is DocumentState.Failed -> "failed"
            DocumentState.Absent -> "absent"
            null -> "loading"
        }
        val builder = if (source.isPrewarmed && platform == "installed") Health.up() else Health.down()
        return builder.withDetail("platform", platform).withDetail("prewarmed", source.isPrewarmed).build()
    }
}
