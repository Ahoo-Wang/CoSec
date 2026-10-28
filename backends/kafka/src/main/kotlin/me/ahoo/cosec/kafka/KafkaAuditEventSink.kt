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
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.spi.audit.AuditEventSink
import me.ahoo.cosec.runtime.json.CoSecJson
import org.springframework.beans.factory.DisposableBean
import org.springframework.kafka.core.KafkaOperations
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_PENDING_AUDIT_EVENTS = 1024
private const val KAFKA_AUDIT_THREAD_NAME = "cosec-kafka-audit"
private val DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10)

class KafkaAuditEventSink(
    private val kafkaOperations: KafkaOperations<String, String>,
    private val topic: String,
    executor: Executor? = null,
    private val shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
) : AuditEventSink,
    DisposableBean {
    init {
        require(topic.isNotBlank()) { "topic must not be blank." }
    }

    /** One thread with a bounded queue keeps the send order; a full queue rejects, and the event is dropped. */
    private val owned: ExecutorService? = if (executor == null) {
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(MAX_PENDING_AUDIT_EVENTS)) { task ->
            Thread(task, KAFKA_AUDIT_THREAD_NAME).apply { isDaemon = true }
        }
    } else {
        null
    }
    private val executor: Executor = executor ?: owned!!
    private val queueFullWarningLogged = AtomicBoolean()

    @Suppress("TooGenericExceptionCaught")
    override fun publish(event: AuditEvent) {
        try {
            // ponytail: one bounded scheduler preserves order; shard by key if throughput becomes a bottleneck.
            this.executor.execute {
                queueFullWarningLogged.set(false)
                try {
                    kafkaOperations.send(
                        topic,
                        CoSecJson.mapper.writeValueAsString(arrayOf(event.tenantId, event.principal.id)),
                        CoSecJson.mapper.writeValueAsString(event),
                    ).whenComplete { _, error ->
                        if (error != null) {
                            logFailure(error)
                        }
                    }
                } catch (error: Exception) {
                    logFailure(error)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (queueFullWarningLogged.compareAndSet(false, true)) {
                log.warn { "Dropping audit events because the Kafka audit queue is full or shutting down." }
            }
        }
    }

    override fun destroy() {
        val owned = owned ?: return
        owned.shutdown()
        try {
            if (!owned.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn { "Timed out while draining the Kafka audit queue; forcing shutdown." }
                owned.shutdownNow()
            }
        } catch (error: InterruptedException) {
            owned.shutdownNow()
            Thread.currentThread().interrupt()
            log.warn(error) { "Interrupted while draining the Kafka audit queue; forcing shutdown." }
        }
    }

    private fun logFailure(error: Throwable) {
        log.warn(error) { "Failed to publish audit event to Kafka topic [$topic]." }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
