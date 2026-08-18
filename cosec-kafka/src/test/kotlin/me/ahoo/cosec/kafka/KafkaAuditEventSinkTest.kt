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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.audit.AuditAuthorizationData
import me.ahoo.cosec.audit.AuditDeviceData
import me.ahoo.cosec.audit.AuditEventData
import me.ahoo.cosec.audit.AuditPolicyData
import me.ahoo.cosec.audit.AuditPrincipalData
import me.ahoo.cosec.audit.AuditRequestData
import me.ahoo.cosec.audit.AuditSourceData
import me.ahoo.cosec.audit.AuditStatementData
import me.ahoo.cosec.audit.AuditTraceData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class KafkaAuditEventSinkTest {
    private val sinks = mutableListOf<KafkaAuditEventSink>()

    private val event = AuditEventData(
        eventId = "event-1",
        timestamp = Instant.parse("2026-08-18T00:00:00Z"),
        tenantId = "tenant-1",
        principal = AuditPrincipalData(
            id = "principal-1",
            authenticated = true,
            roles = setOf("admin"),
            policies = setOf("orders"),
        ),
        request = AuditRequestData(
            id = "request-1",
            appId = "app-1",
            spaceId = "space-1",
            remoteIp = "127.0.0.1",
            method = "GET",
            path = "/orders/1",
            routeId = "orders",
            device = AuditDeviceData(id = "device-1", userAgent = "test-agent"),
        ),
        authorization = AuditAuthorizationData(
            decision = AuditDecision.ALLOW,
            reasonCode = AuditReasonCode.ALLOW,
            reason = "Allow",
            elapsedNanos = 100,
            source = AuditSourceData(
                type = AuditSourceType.GLOBAL_POLICY,
                policy = AuditPolicyData(
                    id = "orders",
                    type = PolicyType.GLOBAL,
                    statement = AuditStatementData(index = 0, name = "read"),
                ),
            ),
        ),
        trace = AuditTraceData(traceId = "trace-1", spanId = "span-1"),
    )

    @AfterEach
    fun destroySinks() {
        sinks.forEach(KafkaAuditEventSink::destroy)
    }

    private fun sink(
        kafkaOperations: KafkaOperations<String, String>,
        scheduler: Scheduler = Schedulers.immediate(),
    ): KafkaAuditEventSink {
        return KafkaAuditEventSink(kafkaOperations, "cosec-audit", scheduler).also(sinks::add)
    }

    @Test
    fun publishSchedulesJsonSend() {
        val result = CompletableFuture<SendResult<String, String>>()
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), any()) } returns result
        }
        var scheduledTask: Runnable? = null
        val scheduler = Schedulers.fromExecutor(Executor { scheduledTask = it })
        val sink = sink(kafkaOperations, scheduler)

        sink.publish(event)

        verify(exactly = 0) { kafkaOperations.send(any(), any(), any()) }
        scheduledTask!!.run()
        verify(exactly = 1) {
            kafkaOperations.send(
                "cosec-audit",
                "[\"tenant-1\",\"principal-1\"]",
                CoSecJsonSerializer.writeValueAsString(event),
            )
        }
        result.complete(mockk())
    }

    @Test
    fun keysAreUnambiguous() {
        val keys = mutableListOf<String>()
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), capture(keys), any()) } returns CompletableFuture.completedFuture(mockk())
        }
        val sink = sink(kafkaOperations)

        sink.publish(event.copy(tenantId = "a", principal = event.principal.copy(id = "b:c")))
        sink.publish(event.copy(tenantId = "a:b", principal = event.principal.copy(id = "c")))

        keys[0].assert().isEqualTo("[\"a\",\"b:c\"]")
        keys[1].assert().isEqualTo("[\"a:b\",\"c\"]")
        keys.toSet().assert().hasSize(2)
    }

    @Test
    fun serializesFieldsByOwner() {
        val json = CoSecJsonSerializer.readTree(CoSecJsonSerializer.writeValueAsString(event))

        json.has("principalId").assert().isFalse()
        json.has("requestId").assert().isFalse()
        json.has("decision").assert().isFalse()
        json.get("principal").get("id").asString().assert().isEqualTo("principal-1")
        json.get("request").get("id").asString().assert().isEqualTo("request-1")
        json.get("authorization").get("source").get("policy").get("id").asString().assert()
            .isEqualTo("orders")
        json.get("trace").get("traceId").asString().assert().isEqualTo("trace-1")
    }

    @Test
    fun asyncFailureDoesNotEscapePublish() {
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), any()) } returns CompletableFuture.failedFuture(
                IllegalStateException("Kafka unavailable"),
            )
        }

        sink(kafkaOperations).publish(event)
    }

    @Test
    fun synchronousFailureDoesNotEscapeScheduledTask() {
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), any()) } throws IllegalStateException("Kafka unavailable")
        }

        sink(kafkaOperations).publish(event)
    }

    @Test
    fun blankTopicIsRejected() {
        assertThrows<IllegalArgumentException> {
            KafkaAuditEventSink(mockk(), " ")
        }
    }

    @Test
    fun serialSchedulerPreservesSendOrder() {
        val values = mutableListOf<String>()
        val sent = CountDownLatch(2)
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), capture(values)) } answers {
                sent.countDown()
                CompletableFuture.completedFuture(mockk())
            }
        }
        val sink = KafkaAuditEventSink(kafkaOperations, "cosec-audit").also(sinks::add)
        val first = event.copy(request = event.request.copy(id = "request-1"))
        val second = event.copy(request = event.request.copy(id = "request-2"))

        sink.publish(first)
        sink.publish(second)

        sent.await(5, TimeUnit.SECONDS).assert().isTrue()
        values[0].assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(first))
        values[1].assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(second))
    }

    @Test
    fun rejectedTasksAreDropped() {
        val scheduler = mockk<Scheduler>(relaxed = true) {
            every { schedule(any<Runnable>()) } throws RejectedExecutionException("Queue full")
        }
        val kafkaOperations = mockk<KafkaOperations<String, String>>(relaxed = true)
        val sink = sink(kafkaOperations, scheduler)

        sink.publish(event)
        sink.publish(event)

        verify(exactly = 0) { kafkaOperations.send(any(), any(), any()) }
    }

    @Test
    fun destroyDoesNotDisposeCallerScheduler() {
        val scheduler = mockk<Scheduler>(relaxed = true)
        val sink = KafkaAuditEventSink(mockk(), "cosec-audit", scheduler)

        sink.destroy()

        verify(exactly = 0) { scheduler.disposeGracefully() }
        verify(exactly = 0) { scheduler.dispose() }
    }

    @Test
    fun destroyDrainsAcceptedTasks() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val sent = CountDownLatch(2)
        var sendCount = 0
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), any()) } answers {
                if (sendCount++ == 0) {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                sent.countDown()
                CompletableFuture.completedFuture(mockk())
            }
        }
        val sink = KafkaAuditEventSink(kafkaOperations, "cosec-audit")

        sink.publish(event.copy(request = event.request.copy(id = "request-1")))
        firstStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
        sink.publish(event.copy(request = event.request.copy(id = "request-2")))
        val destroyed = CompletableFuture.runAsync(sink::destroy)
        destroyed.isDone.assert().isFalse()
        releaseFirst.countDown()

        destroyed.get(5, TimeUnit.SECONDS)
        sent.await(5, TimeUnit.SECONDS).assert().isTrue()
        verify(exactly = 2) { kafkaOperations.send(any(), any(), any()) }
    }

    @Test
    fun destroyForcesShutdownAfterTimeout() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), any()) } answers {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                CompletableFuture.completedFuture(mockk())
            }
        }
        val sink = KafkaAuditEventSink(
            kafkaOperations = kafkaOperations,
            topic = "cosec-audit",
            shutdownTimeout = Duration.ofMillis(1),
        )

        sink.publish(event)
        started.await(5, TimeUnit.SECONDS).assert().isTrue()
        sink.destroy()
        release.countDown()
    }
}
