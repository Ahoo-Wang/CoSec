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

package me.ahoo.cosec.control.audit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `CTL-66` at shutdown: [ChangeListeners.drain] stops taking events, lets the consumers deliver what is queued within
 * the drain timeout, counts what is left as kept, and only then stops them. A gated step arriving meanwhile is refused
 * before it takes effect; another step's event is kept, never queued to be lost.
 */
class ChangeListenersDrainTest {
    private val opened = ArrayList<ChangeListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    /** Records every event once [gate] opens. */
    private class Gated(override val name: String, override val delivery: Delivery) : ChangeListener {
        val gate = CompletableDeferred<Unit>()
        val received = CopyOnWriteArrayList<String>()

        override suspend fun onChange(event: ChangeEvent) {
            gate.await()
            received += "${event.change} ${event.kind}"
        }
    }

    private fun event(kind: ChangeEvent.Kind, change: String = "c1") = ChangeEvent(
        "$change:$kind",
        Instant.EPOCH,
        kind,
        Actor(TenantId.PLATFORM, PrincipalId("plato")),
        ChangeId(change).value,
        SnapshotKey.Tenant("acme"),
        null,
        null,
    )

    private fun listeners(vararg listeners: ChangeListener, drainTimeout: Duration) =
        ChangeListeners(listeners.toList(), 5.seconds, drainTimeout = drainTimeout).also { opened += it }

    private suspend fun drain(changes: ChangeListeners): Boolean = withContext(Dispatchers.IO) { changes.drain() }

    @Test
    fun `a backlog at shutdown is delivered within the drain timeout, then the consumers stop - CTL-66`() =
        runBlocking<Unit> {
            val audit = Gated("audit", Delivery.REQUIRED)
            val kafka = Gated("kafka", Delivery.BEST_EFFORT)
            val changes = listeners(audit, kafka, drainTimeout = 5.seconds)
            listOf(ChangeEvent.Kind.CHECKED, ChangeEvent.Kind.SHADOW_SKIPPED, ChangeEvent.Kind.ABANDONED)
                .forEach { changes.publish(event(it)) }
            // The listeners answer only once the drain has begun: what they deliver then is the drain's doing.
            launch {
                delay(100)
                audit.gate.complete(Unit)
                kafka.gate.complete(Unit)
            }
            drain(changes).assert().isTrue()
            val all = listOf("c1 CHECKED", "c1 SHADOW_SKIPPED", "c1 ABANDONED")
            audit.received.assert().containsExactlyElementsOf(all)
            kafka.received.assert().containsExactlyElementsOf(all)
            changes.queues.forEach { it.kept.assert().describedAs(it.listener.name).isEqualTo(0L) }
        }

    @Test
    fun `what a held listener cannot take within the drain timeout is counted as kept, then stopped - CTL-66`() =
        runBlocking<Unit> {
            val held = Gated("kafka", Delivery.BEST_EFFORT)
            val audit = Gated("audit", Delivery.REQUIRED).apply { gate.complete(Unit) }
            val changes = listeners(held, audit, drainTimeout = 200.milliseconds)
            changes.publish(event(ChangeEvent.Kind.CHECKED))
            changes.publish(event(ChangeEvent.Kind.SHADOW_SKIPPED))
            drain(changes).assert().isFalse()
            val (kafkaQueue, auditQueue) = changes.queues
            // The one being delivered and the one behind it.
            kafkaQueue.kept.assert().isEqualTo(2L)
            auditQueue.kept.assert().isEqualTo(0L)
            audit.received.assert().containsExactly("c1 CHECKED", "c1 SHADOW_SKIPPED")
            held.gate.complete(Unit)
            delay(100)
            held.received.assert().isEmpty()
        }

    @Test
    fun `during the drain a gated step is refused and another step's event is kept, never queued - CTL-66`() =
        runBlocking<Unit> {
            val audit = Gated("audit", Delivery.REQUIRED)
            val kafka = Gated("kafka", Delivery.BEST_EFFORT)
            val changes = listeners(audit, kafka, drainTimeout = 1.seconds)
            changes.publish(event(ChangeEvent.Kind.CHECKED))
            val drained = async { drain(changes) }
            delay(100)
            // A gated step: refused before it takes effect, as a REQUIRED listener that cannot record it (503).
            runCatching { changes.publish(event(ChangeEvent.Kind.PUBLISHING)) }.exceptionOrNull()
                .assert().isInstanceOf(ChangeAuditException::class.java)
            // A stored step's event: kept on every listener, the step not failed.
            changes.publish(event(ChangeEvent.Kind.SHADOW_SKIPPED))
            changes.queues.forEach { it.depth.assert().describedAs(it.listener.name).isEqualTo(1) }
            audit.gate.complete(Unit)
            kafka.gate.complete(Unit)
            drained.await().assert().isTrue()
            audit.received.assert().containsExactly("c1 CHECKED")
            kafka.received.assert().containsExactly("c1 CHECKED")
            changes.queues.forEach { it.kept.assert().describedAs(it.listener.name).isEqualTo(1L) }
        }

    @Test
    fun `with no REQUIRED listener a gated step's event during the drain is kept, the step not refused - CTL-66`() =
        runBlocking<Unit> {
            val kafka = Gated("kafka", Delivery.BEST_EFFORT).apply { gate.complete(Unit) }
            val changes = listeners(kafka, drainTimeout = 1.seconds)
            drain(changes).assert().isTrue()
            changes.publish(event(ChangeEvent.Kind.PUBLISHING))
            changes.queues.single().kept.assert().isEqualTo(1L)
            kafka.received.assert().isEmpty()
        }

    @Test
    fun `without listeners publishing and draining do nothing - CTL-66`() = runBlocking<Unit> {
        val changes = listeners(drainTimeout = 1.seconds)
        changes.publish(event(ChangeEvent.Kind.PUBLISHING))
        drain(changes).assert().isTrue()
        changes.publish(event(ChangeEvent.Kind.PUBLISHING))
        changes.queues.assert().isEmpty()
    }
}
