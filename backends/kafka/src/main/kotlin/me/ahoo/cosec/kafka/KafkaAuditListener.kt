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

package me.ahoo.cosec.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.audit.AuditEvents
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.json.CoSecJson
import org.springframework.beans.factory.DisposableBean
import org.springframework.kafka.core.KafkaOperations
import kotlin.time.Duration

/**
 * Writes each decision event to [topic] as its audit record (RFC 0006 §9.5.3): the key is `[tenantId, principalId]`
 * and the value the audit event's JSON.
 *
 * - [Delivery.BEST_EFFORT] (`cosec.audit.kafka.delivery=best-effort`, the default): the pipeline's bounded queue drops
 *   events while Kafka falls behind; a failed send is logged.
 * - [Delivery.REQUIRED] (`cosec.audit.kafka.delivery=required`): the call waits for the broker's acknowledgement, so
 *   a permit that cannot be audited is a 503 (§9.4). Configure the producer with `acks=all`.
 *
 * Sends run on this listener's own [BoundedSender]: at most [queueCapacity] waiting, a full queue fails the send at
 * once (a REQUIRED delivery that fails is a 503 for a permit), and a send whose delivery was cancelled (it timed out)
 * before its turn is dropped, not sent.
 *
 * [destroy] waits up to [drainTimeout] for what this listener still holds, so Spring destroys [kafkaOperations] only
 * after it.
 */
class KafkaAuditListener(
    private val kafkaOperations: KafkaOperations<String, String>,
    private val topic: String,
    override val delivery: Delivery = Delivery.BEST_EFFORT,
    override val name: String = NAME,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    /** `cosec.observability.listener.drain-timeout`: how long [destroy] waits for the sends this listener holds. */
    private val drainTimeout: Duration = DecisionListeners.DEFAULT_DRAIN_TIMEOUT,
) : DecisionListener,
    DisposableBean {
    init {
        require(topic.isNotBlank()) { "topic must not be blank." }
    }

    private val sender = BoundedSender(
        "Kafka audit queue of [$topic]",
        THREAD_NAME,
        queueCapacity,
        drainTimeout,
    )

    override suspend fun onDecision(event: DecisionEvent) {
        val audit = AuditEvents.from(event)
        val key = CoSecJson.mapper.writeValueAsString(arrayOf(audit.tenantId, audit.principal.id))
        val value = CoSecJson.mapper.writeValueAsString(audit)
        val queued = sender.send { kafkaOperations.send(topic, key, value) }
        if (delivery == Delivery.REQUIRED) {
            queued.await()
            return
        }
        // Not awaited: the pipeline's consumer takes one event at a time, so waiting for each answer would cap this
        // listener at one record per round trip and defeat the producer's batching.
        queued.sent.whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Failed to publish audit event to Kafka topic [$topic]." }
            }
        }
    }

    /**
     * Stops taking records and waits up to [drainTimeout] for the sends queued here, then for the broker's answers to
     * those already made ([BoundedSender.close]). A blocking call, for a host's shutdown.
     */
    override fun destroy() {
        sender.close()
    }

    override fun toString(): String = "KafkaAuditListener($topic, $delivery)"

    companion object {
        const val NAME = "kafka-audit"
        const val DEFAULT_QUEUE_CAPACITY = 1024
        private const val THREAD_NAME = "cosec-kafka-audit"
        private val log = KotlinLogging.logger {}
    }
}
