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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import me.ahoo.cosec.runtime.audit.AuditEvents
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.beans.factory.DisposableBean
import org.springframework.kafka.core.KafkaOperations
import java.util.concurrent.Executors

/**
 * Writes each decision event to [topic] as its audit record (RFC 0006 §9.5.3): the key is `[tenantId, principalId]`
 * and the value the audit event's JSON, as `KafkaAuditEventSink` writes them.
 *
 * - [Delivery.BEST_EFFORT] (`cosec.audit.kafka.delivery=best-effort`, the default): the pipeline's bounded queue drops
 *   events while Kafka falls behind; a failed send is logged.
 * - [Delivery.REQUIRED] (`cosec.audit.kafka.delivery=required`): the call waits for the broker's acknowledgement, so
 *   a permit that cannot be audited is a 503 (§9.4). Configure the producer with `acks=all`.
 *
 * `KafkaOperations.send` may block while the producer waits for metadata or buffer space, so it runs on this listener's
 * own thread, never on the request's or a shared pool's.
 */
class KafkaAuditListener(
    private val kafkaOperations: KafkaOperations<String, String>,
    private val topic: String,
    override val delivery: Delivery = Delivery.BEST_EFFORT,
    override val name: String = NAME,
) : DecisionListener,
    DisposableBean {
    init {
        require(topic.isNotBlank()) { "topic must not be blank." }
    }

    private val sender: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, THREAD_NAME).apply { isDaemon = true } }.asCoroutineDispatcher()

    override suspend fun onDecision(event: DecisionEvent) {
        val audit = AuditEvents.from(event)
        val key = CoSecJsonSerializer.writeValueAsString(arrayOf(audit.tenantId, audit.principal.id))
        val value = CoSecJsonSerializer.writeValueAsString(audit)
        val sent = withContext(sender) { kafkaOperations.send(topic, key, value) }
        if (delivery == Delivery.REQUIRED) {
            sent.await()
            return
        }
        sent.whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Failed to publish audit event to Kafka topic [$topic]." }
            }
        }
    }

    override fun destroy() {
        sender.close()
    }

    override fun toString(): String = "KafkaAuditListener($topic, $delivery)"

    companion object {
        const val NAME = "kafka-audit"
        private const val THREAD_NAME = "cosec-kafka-audit"
        private val log = KotlinLogging.logger {}
    }
}
