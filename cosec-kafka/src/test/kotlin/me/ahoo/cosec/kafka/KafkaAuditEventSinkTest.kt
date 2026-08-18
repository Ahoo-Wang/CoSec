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
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

class KafkaAuditEventSinkTest {
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

    @Test
    fun publishJsonWithoutWaitingForKafka() {
        val result = CompletableFuture<SendResult<String, String>>()
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), any()) } returns result
        }
        val sink = KafkaAuditEventSink(kafkaOperations, "cosec-audit")

        sink.publish(event)

        verify(exactly = 1) {
            kafkaOperations.send(
                "cosec-audit",
                "tenant-1:principal-1",
                CoSecJsonSerializer.writeValueAsString(event),
            )
        }
        result.isDone.assert().isFalse()
    }

    @Test
    fun asyncFailureDoesNotEscapePublish() {
        val kafkaOperations = mockk<KafkaOperations<String, String>> {
            every { send(any(), any(), any()) } returns CompletableFuture.failedFuture(
                IllegalStateException("Kafka unavailable"),
            )
        }

        KafkaAuditEventSink(kafkaOperations, "cosec-audit").publish(event)
    }
}
