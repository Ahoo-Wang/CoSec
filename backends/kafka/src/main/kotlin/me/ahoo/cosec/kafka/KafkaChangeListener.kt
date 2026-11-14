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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeEventJson
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import org.springframework.beans.factory.DisposableBean
import org.springframework.kafka.core.KafkaOperations
import kotlin.time.Duration

/**
 * Writes each control-plane change event to [topic] (RFC 0007 §10.5, default `cosec-control-audit`): the key is the
 * document (`platform`, `app:order`, `tenant:acme`), so a document's events stay in order, and the value the event's
 * JSON ([ChangeEventJson]).
 *
 * - [Delivery.REQUIRED]: publishing and creating break-glass wait for the broker's acknowledgement, so a step Kafka
 *   cannot record does not happen (503). Configure the producer with `acks=all`. Break-glass needs one (RFC 0007 §8).
 * - [Delivery.BEST_EFFORT] (the default): a failed send is reported to the control plane, which logs it.
 *
 * As [KafkaAuditListener] does, sends run on this listener's own [BoundedSender], with at most [queueCapacity] waiting,
 * never on the caller's: `KafkaOperations.send` may block on metadata or buffer space. [destroy] waits up to
 * [drainTimeout] for what is queued and unanswered, as the audit listener does, so a change event made just before
 * shutdown is not lost (round-2 review L2).
 */
class KafkaChangeListener(
    private val kafkaOperations: KafkaOperations<String, String>,
    private val topic: String = DEFAULT_TOPIC,
    override val delivery: Delivery = Delivery.BEST_EFFORT,
    override val name: String = NAME,
    queueCapacity: Int = KafkaAuditListener.DEFAULT_QUEUE_CAPACITY,
    /** `cosec.observability.listener.drain-timeout`: how long [destroy] waits for the sends this listener holds. */
    drainTimeout: Duration = DecisionListeners.DEFAULT_DRAIN_TIMEOUT,
) : ChangeListener,
    DisposableBean {
    init {
        require(topic.isNotBlank()) { "topic must not be blank." }
    }

    private val sender = BoundedSender("Kafka change audit queue of [$topic]", THREAD_NAME, queueCapacity, drainTimeout)

    override suspend fun onChange(event: ChangeEvent) {
        val key = event.document.toString()
        val value = ChangeEventJson.write(event)
        sender.send { kafkaOperations.send(topic, key, value) }.await()
    }

    override fun destroy() {
        sender.close()
    }

    override fun toString(): String = "KafkaChangeListener($topic, $delivery)"

    companion object {
        const val NAME = "kafka-change-audit"
        const val DEFAULT_TOPIC = "cosec-control-audit"
        private const val THREAD_NAME = "cosec-kafka-change-audit"
    }
}
