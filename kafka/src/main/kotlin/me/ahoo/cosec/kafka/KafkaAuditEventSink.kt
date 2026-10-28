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
import org.springframework.beans.factory.DisposableBean
import org.springframework.kafka.core.KafkaOperations
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_PENDING_AUDIT_EVENTS = 1024
private const val KAFKA_AUDIT_SCHEDULER_NAME = "cosec-kafka-audit"
private val DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10)

class KafkaAuditEventSink(
    private val kafkaOperations: KafkaOperations<String, String>,
    private val topic: String,
    scheduler: Scheduler? = null,
    private val shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
) : AuditEventSink,
    DisposableBean {
    init {
        require(topic.isNotBlank()) { "topic must not be blank." }
    }

    private val ownsScheduler = scheduler == null
    private val scheduler = scheduler ?: Schedulers.newBoundedElastic(
        1,
        MAX_PENDING_AUDIT_EVENTS,
        KAFKA_AUDIT_SCHEDULER_NAME,
    )
    private val queueFullWarningLogged = AtomicBoolean()

    @Suppress("TooGenericExceptionCaught")
    override fun publish(event: AuditEvent) {
        try {
            // ponytail: one bounded scheduler preserves order; shard by key if throughput becomes a bottleneck.
            scheduler.schedule {
                queueFullWarningLogged.set(false)
                try {
                    kafkaOperations.send(
                        topic,
                        CoSecJsonSerializer.writeValueAsString(arrayOf(event.tenantId, event.principal.id)),
                        CoSecJsonSerializer.writeValueAsString(event),
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

    @Suppress("TooGenericExceptionCaught")
    override fun destroy() {
        if (!ownsScheduler) {
            return
        }
        try {
            scheduler.disposeGracefully().block(shutdownTimeout)
        } catch (error: RuntimeException) {
            log.warn(error) { "Timed out while draining the Kafka audit queue; forcing shutdown." }
            scheduler.dispose()
        }
    }

    private fun logFailure(error: Throwable) {
        log.warn(error) { "Failed to publish audit event to Kafka topic [$topic]." }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
