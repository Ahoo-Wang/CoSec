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

package me.ahoo.cosec.spring.boot.micrometer

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.kafka.KafkaAuditListener

/**
 * `cosec.audit.kafka.failures`, tagged with the listener: the BEST_EFFORT audit records lost after they were queued, the
 * broker refusing them or never answering (DR1-G-4). The decision listeners' `cosec.listener.failures` counts only what
 * fails before a record is queued, so while the broker is down every record is lost and that counter stays still until
 * the producer's buffer and the queue fill: alert on this one.
 */
@InternalCoSecApi
object KafkaAuditMeters {
    const val FAILURES = "cosec.audit.kafka.failures"
    private const val LISTENER_TAG = "listener"

    fun bind(registry: MeterRegistry, listener: KafkaAuditListener) {
        FunctionCounter.builder(FAILURES, listener) { it.sendFailures.toDouble() }
            .tag(LISTENER_TAG, listener.name)
            .description("Audit records a BEST_EFFORT Kafka send lost after they were queued")
            .register(registry)
    }
}
