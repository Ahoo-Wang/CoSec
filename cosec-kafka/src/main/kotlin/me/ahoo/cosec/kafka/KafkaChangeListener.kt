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

import kotlinx.coroutines.future.await
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeEventJson
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.runtime.event.Delivery
import org.springframework.beans.factory.DisposableBean
import org.springframework.kafka.core.KafkaOperations
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Writes each control-plane change event to [topic] (RFC 0007 §10.5, default `cosec-control-audit`): the key is the
 * document (`platform`, `app:order`, `tenant:acme`), so a document's events stay in order, and the value the event's
 * JSON ([ChangeEventJson]).
 *
 * - [Delivery.REQUIRED]: publishing and creating break-glass wait for the broker's acknowledgement, so a step Kafka
 *   cannot record does not happen (503). Configure the producer with `acks=all`. Break-glass needs one (RFC 0007 §8).
 * - [Delivery.BEST_EFFORT] (the default): a failed send is reported to the control plane, which logs it.
 *
 * As [KafkaAuditListener] does, sends run on this listener's own thread with at most [queueCapacity] waiting, never on
 * the caller's: `KafkaOperations.send` may block on metadata or buffer space.
 */
class KafkaChangeListener(
    private val kafkaOperations: KafkaOperations<String, String>,
    private val topic: String = DEFAULT_TOPIC,
    override val delivery: Delivery = Delivery.BEST_EFFORT,
    override val name: String = NAME,
    queueCapacity: Int = KafkaAuditListener.DEFAULT_QUEUE_CAPACITY,
) : ChangeListener,
    DisposableBean {
    init {
        require(topic.isNotBlank()) { "topic must not be blank." }
    }

    private val sender = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity)) {
        Thread(it, THREAD_NAME).apply { isDaemon = true }
    }

    override suspend fun onChange(event: ChangeEvent) {
        val key = event.document.toString()
        val value = ChangeEventJson.write(event)
        val queued = try {
            CompletableFuture.supplyAsync({ kafkaOperations.send(topic, key, value) }, sender)
        } catch (e: RejectedExecutionException) {
            throw IllegalStateException("The Kafka change audit queue of [$topic] is full.", e)
        }
        try {
            queued.thenCompose { it }.await()
        } catch (e: CancellationException) {
            queued.cancel(false)
            throw e
        }
    }

    override fun destroy() {
        sender.shutdown()
    }

    override fun toString(): String = "KafkaChangeListener($topic, $delivery)"

    companion object {
        const val NAME = "kafka-change-audit"
        const val DEFAULT_TOPIC = "cosec-control-audit"
        private const val THREAD_NAME = "cosec-kafka-change-audit"
    }
}
