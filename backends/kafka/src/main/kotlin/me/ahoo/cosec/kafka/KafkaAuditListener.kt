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
import kotlinx.coroutines.future.await
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.audit.AuditEvents
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.json.CoSecJson
import org.springframework.beans.factory.DisposableBean
import org.springframework.kafka.core.KafkaOperations
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
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
 * `KafkaOperations.send` may block while the producer waits for metadata or buffer space, so it runs on this listener's
 * own thread, never on the request's or a shared pool's. That thread takes at most [queueCapacity] sends waiting: when
 * they pile up because the broker is slow, a new one fails at once instead of queueing without bound, and a REQUIRED
 * delivery that fails is a 503 for a permit. A send whose delivery was cancelled (it timed out) before its turn is
 * dropped, not sent.
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

    private val sender = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity)) {
        Thread(it, THREAD_NAME).apply { isDaemon = true }
    }

    /** The sends the broker has not answered yet: what [destroy] waits for once the queue is empty. */
    private val inFlight: MutableSet<CompletableFuture<*>> = ConcurrentHashMap.newKeySet()

    override suspend fun onDecision(event: DecisionEvent) {
        val audit = AuditEvents.from(event)
        val key = CoSecJson.mapper.writeValueAsString(arrayOf(audit.tenantId, audit.principal.id))
        val value = CoSecJson.mapper.writeValueAsString(audit)
        val queued = try {
            CompletableFuture.supplyAsync({ kafkaOperations.send(topic, key, value) }, sender)
        } catch (e: RejectedExecutionException) {
            throw IllegalStateException("The Kafka audit queue of [$topic] is full.", e)
        }
        val sent = queued.thenCompose { it }
        inFlight += sent
        sent.whenComplete { _, _ -> inFlight -= sent }
        if (delivery == Delivery.REQUIRED) {
            try {
                sent.await()
            } catch (e: CancellationException) {
                // The delivery timed out or was cancelled: a send still waiting for its turn is not made.
                queued.cancel(false)
                throw e
            }
            return
        }
        // Not awaited: the pipeline's consumer takes one event at a time, so waiting for each answer would cap this
        // listener at one record per round trip and defeat the producer's batching.
        sent.whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Failed to publish audit event to Kafka topic [$topic]." }
            }
        }
    }

    /**
     * Stops taking records and waits up to [drainTimeout] for the sends queued here, then for the broker's answers to
     * those already made. At the bound the queued sends are dropped, and the one in progress is interrupted.
     * A blocking call, for a host's shutdown.
     */
    override fun destroy() {
        sender.shutdown()
        if (!drained()) {
            val dropped = sender.shutdownNow().size
            log.warn {
                "The Kafka audit listener of [$topic] did not finish within $drainTimeout: $dropped queued records are " +
                    "dropped and ${inFlight.size} sends are unanswered."
            }
        }
    }

    private fun drained(): Boolean {
        val deadline = System.nanoTime() + drainTimeout.inWholeNanoseconds
        return try {
            sender.awaitTermination(drainTimeout.inWholeNanoseconds, TimeUnit.NANOSECONDS) &&
                answered(deadline - System.nanoTime())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Whether the broker answered every send within [timeoutNanos]. A send that failed was answered, and is logged. */
    private fun answered(timeoutNanos: Long): Boolean = try {
        CompletableFuture.allOf(*inFlight.toTypedArray()).get(timeoutNanos, TimeUnit.NANOSECONDS)
        true
    } catch (_: ExecutionException) {
        true
    } catch (_: TimeoutException) {
        false
    }

    override fun toString(): String = "KafkaAuditListener($topic, $delivery)"

    companion object {
        const val NAME = "kafka-audit"
        const val DEFAULT_QUEUE_CAPACITY = 1024
        private const val THREAD_NAME = "cosec-kafka-audit"
        private val log = KotlinLogging.logger {}
    }
}
