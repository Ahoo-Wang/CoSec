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
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.audit.ChangeAuditException
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.audit.RevisionRef
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.ShadowSummary
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** RFC 0007 §10.5: change events on Kafka, keyed by document; a REQUIRED one stops a step Kafka cannot record. */
class KafkaChangeListenerTest {
    private val event = ChangeEvent(
        "c1:PUBLISHING:1",
        Instant.parse("2026-11-01T00:00:00Z"),
        ChangeEvent.Kind.PUBLISHING,
        Actor(TenantId("acme"), PrincipalId("alice")),
        ChangeId("c1"),
        SnapshotKey.Tenant("acme"),
        RevisionRef(3, "d"),
        RevisionRef(4, "e"),
        listOf(Escalation("p[0]", "NOT_COVERED", "why")),
        listOf(Confirmation(PolicyId("p"), 0, "f")),
        ShadowSummary(
            mapOf("SAME" to 2L),
            Instant.parse("2026-11-01T00:00:00Z"),
            insufficient = false,
            blocked = false
        ),
        "a note",
    )

    @Test
    fun aRequiredListenerWaitsForTheBrokerAndWritesTheDocumentAsTheKey() = runBlocking<Unit> {
        val key = slot<String>()
        val value = slot<String>()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send("cosec-control-audit", capture(key), capture(value)) } returns
                CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
        }
        val listener = KafkaChangeListener(kafka, delivery = Delivery.REQUIRED)
        ChangeListeners(listOf(listener)).publish(event)
        key.captured.assert().isEqualTo("tenant:acme")
        val json = CoSecJsonSerializer.readTree(value.captured)
        json.get("kind").asString().assert().isEqualTo("PUBLISHING")
        json.get("operator").get("principal").asString().assert().isEqualTo("alice")
        json.get("escalations").get(0).get("reason").asString().assert().isEqualTo("NOT_COVERED")
        json.get("confirmations").get(0).get("digest").asString().assert().isEqualTo("f")
        json.get("shadow").get("counts").get("SAME").asLong().assert().isEqualTo(2L)
        listener.toString().assert().contains("REQUIRED")
        listener.destroy()
    }

    @Test
    fun aBrokerThatRejectsStopsAGatedStep() {
        val kafka = mockk<KafkaOperations<String, String>> {
            every {
                send(any<String>(), any<String>(), any<String>())
            } returns CompletableFuture.failedFuture(IllegalStateException("broker down"))
        }
        val listener = KafkaChangeListener(kafka, delivery = Delivery.REQUIRED)
        assertThrownBy<ChangeAuditException> { runBlocking { ChangeListeners(listOf(listener)).publish(event) } }
        assertThrownBy<IllegalArgumentException> { KafkaChangeListener(kafka, " ") }
        listener.destroy()
    }
}
