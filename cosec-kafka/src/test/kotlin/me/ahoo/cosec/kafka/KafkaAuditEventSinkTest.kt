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
import me.ahoo.cosec.api.audit.AuditDevice
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditMatch
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class KafkaAuditEventSinkTest {
    private val sinks = mutableListOf<KafkaAuditEventSink>()

    private val event = AuditEvent(
        timestamp = Instant.parse("2026-08-18T00:00:00Z"),
        tenantId = "tenant-1",
        principalId = "principal-1",
        authenticated = true,
        roles = setOf("admin"),
        policies = setOf("orders"),
        appId = "app-1",
        spaceId = "space-1",
        device = AuditDevice(id = "device-1", userAgent = "test-agent"),
        requestId = "request-1",
        remoteIp = "127.0.0.1",
        method = "GET",
        path = "/orders/1",
        decision = AuditDecision.ALLOW,
        reason = "Allow",
        elapsedNanos = 100,
        match = AuditMatch(policyId = "orders", statementName = "read"),
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

        sink.publish(event.copy(tenantId = "a", principalId = "b:c"))
        sink.publish(event.copy(tenantId = "a:b", principalId = "c"))

        keys[0].assert().isEqualTo("[\"a\",\"b:c\"]")
        keys[1].assert().isEqualTo("[\"a:b\",\"c\"]")
        keys.toSet().assert().hasSize(2)
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
        val first = event.copy(requestId = "request-1")
        val second = event.copy(requestId = "request-2")

        sink.publish(first)
        sink.publish(second)

        sent.await(5, TimeUnit.SECONDS).assert().isTrue()
        values[0].assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(first))
        values[1].assert().isEqualTo(CoSecJsonSerializer.writeValueAsString(second))
    }
}
