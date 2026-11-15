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
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * `cosecSnapshot`: UP once the platform document and the prewarmed apps and tenants are installed ([SnapshotReadiness]
 * gates readiness on the same [DocumentReadiness]), DOWN before, with what is missing. Add it to the readiness group for
 * its details.
 */
@InternalCoSecApi
class SnapshotHealthIndicator(
    private val source: SnapshotSource<*, *>,
    private val readiness: DocumentReadiness,
) : HealthIndicator {
    override fun health(): Health {
        // Stored documents that do not compile here, by key and revision: never the compiler's message.
        val invalid = source.entries().filter { it.invalid }
        val platform = when (source.peek()?.policies) {
            is DocumentState.Present<*> -> "installed"
            is DocumentState.Failed -> if (invalid.any { it.key == SnapshotKey.Platform }) "invalid" else "failed"
            DocumentState.Absent -> "absent"
            null -> "loading"
        }
        val missing = readiness.missing()
        val builder = if (missing.isEmpty()) Health.up() else Health.down()
        builder.withDetail("platform", platform)
            .withDetail("prewarmed", source.isPrewarmed)
            .withDetail("missing", missing.map { it.toString() })
        if (invalid.isNotEmpty()) {
            builder.withDetail("invalid", invalid.map { "${it.key}@${it.revision}" })
        }
        return builder.build()
    }
}
