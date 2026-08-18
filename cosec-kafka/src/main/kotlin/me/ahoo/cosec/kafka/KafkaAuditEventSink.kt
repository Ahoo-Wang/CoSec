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

package me.ahoo.cosec.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.kafka.core.KafkaOperations

class KafkaAuditEventSink(
    private val kafkaOperations: KafkaOperations<String, String>,
    private val topic: String,
) : AuditEventSink {
    init {
        require(topic.isNotBlank()) { "topic must not be blank." }
    }

    override fun publish(event: AuditEvent) {
        kafkaOperations.send(
            topic,
            "${event.tenantId}:${event.principalId}",
            CoSecJsonSerializer.writeValueAsString(event),
        ).whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Failed to publish audit event to Kafka topic [$topic]." }
            }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
