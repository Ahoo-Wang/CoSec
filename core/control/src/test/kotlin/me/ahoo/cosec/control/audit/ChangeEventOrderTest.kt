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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `CTL-66`: a listener receives one change's events in the order this instance wrote them, whatever its delivery and
 * however long it takes; a full queue drops the incoming event of a BEST_EFFORT listener (a gap, never a reordering),
 * fails a gated step of a REQUIRED one, and loses, counted, the event of a stored step for a REQUIRED one. A stall is
 * reported, never acted on.
 */
class ChangeEventOrderTest {
    private val opened = ArrayList<ChangeListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    /** Records every event; the first [held] calls wait for [gate]. */
    private class Held(
        override val name: String,
        override val delivery: Delivery,
        private val held: Int = 1,
        /** Whether a held call may be cancelled (by a REQUIRED listener's call bound); a test of a full queue needs not. */
        private val cancellable: Boolean = true,
        private val pause: suspend () -> Unit = {},
    ) : ChangeListener {
        val gate = CompletableDeferred<Unit>()
        val received = CopyOnWriteArrayList<String>()
        private var calls = 0

        override suspend fun onChange(event: ChangeEvent) {
            if (calls++ < held) {
                if (cancellable) gate.await() else withContext(NonCancellable) { gate.await() }
            }
            pause()
            received += "${event.change.value} ${event.kind}"
        }
    }

    private fun event(kind: ChangeEvent.Kind, change: String = "c1", note: String = "") = ChangeEvent(
        "$change:$kind",
        Instant.EPOCH,
        kind,
        Actor(TenantId.PLATFORM, PrincipalId("plato")),
        ChangeId(change),
        SnapshotKey.Tenant("acme"),
        null,
        null,
        note = note,
    )

    private fun listeners(
        vararg listeners: ChangeListener,
        requiredTimeout: Duration = 5.seconds,
        heap: Long = Runtime.getRuntime().maxMemory(),
    ) = ChangeListeners(listeners.toList(), requiredTimeout, heap).also { opened += it }

    /** A heap whose share holds [events] events, for one listener, sized as the largest event these tests write. */
    private fun heapFor(events: Int) =
        ChangeListeners.HEAP_SHARE * events * ChangeEventSize.of(event(ChangeEvent.Kind.SHADOW_STARTED))

    @Test
    fun `a listener receives a change's events in the order they were written, the first still held - CTL-66`() =
        runBlocking<Unit> {
            for (delivery in Delivery.entries) {
                val listener = Held("held-$delivery", delivery)
                val changes = listeners(listener)
                changes.publish(event(ChangeEvent.Kind.CHECKED))
                changes.publish(event(ChangeEvent.Kind.SHADOW_SKIPPED))
                listener.gate.complete(Unit)
                changes.awaitDelivered()
                listener.received.assert().describedAs("$delivery").containsExactly("c1 CHECKED", "c1 SHADOW_SKIPPED")
            }
        }

    @Test
    fun `a gated step waits for what its REQUIRED listener has queued before it, then is recorded after it - CTL-66`() =
        runBlocking<Unit> {
            val required = Held("audit", Delivery.REQUIRED)
            val after = Held("log", Delivery.BEST_EFFORT, held = 0)
            val changes = listeners(required, after)
            changes.publish(event(ChangeEvent.Kind.CHECKED))
            val publishing = async(Dispatchers.Default) { changes.publish(event(ChangeEvent.Kind.PUBLISHING)) }
            delay(100)
            publishing.isCompleted.assert().describedAs("the gated step waits for the earlier event").isFalse()
            required.gate.complete(Unit)
            publishing.await()
            changes.publish(event(ChangeEvent.Kind.PUBLISHED))
            changes.awaitDelivered()
            required.received.assert().containsExactly("c1 CHECKED", "c1 PUBLISHING", "c1 PUBLISHED")
            after.received.assert().containsExactly("c1 CHECKED", "c1 PUBLISHING", "c1 PUBLISHED")
        }

    @Test
    fun `under concurrent changes and slow listeners, each change's events arrive in order - CTL-66`() =
        runBlocking<Unit> {
            val random = Random(66)
            val slow = Held("slow", Delivery.BEST_EFFORT, held = 0) { delay(random.nextLong(0, 3)) }
            val audit = Held("audit", Delivery.REQUIRED, held = 0) { delay(random.nextLong(0, 3)) }
            val changes = listeners(slow, audit)
            val kinds = listOf(
                ChangeEvent.Kind.CHECKED,
                ChangeEvent.Kind.SHADOW_STARTED,
                ChangeEvent.Kind.SHADOWED,
                ChangeEvent.Kind.APPROVED,
                ChangeEvent.Kind.PUBLISHING,
                ChangeEvent.Kind.PUBLISHED,
            )
            withContext(Dispatchers.Default) {
                (1..20).map { n ->
                    async { kinds.forEach { changes.publish(event(it, "c$n")) } }
                }.awaitAll()
            }
            changes.awaitDelivered()
            for (listener in listOf(slow, audit)) {
                for (n in 1..20) {
                    listener.received.filter { it.startsWith("c$n ") }.assert().describedAs("${listener.name} c$n")
                        .isEqualTo(kinds.map { "c$n $it" })
                }
            }
        }

    @Test
    fun `a full BEST_EFFORT queue drops the incoming event, so what arrives is an ordered prefix - CTL-66`() =
        runBlocking<Unit> {
            val listener = Held("kafka", Delivery.BEST_EFFORT)
            val changes = listeners(listener, heap = heapFor(2))
            listOf(ChangeEvent.Kind.CHECKED, ChangeEvent.Kind.SHADOW_STARTED, ChangeEvent.Kind.SHADOWED)
                .forEach { changes.publish(event(it)) }
            changes.queues.single().dropped.assert().isEqualTo(1L)
            listener.gate.complete(Unit)
            changes.awaitDelivered()
            listener.received.assert().containsExactly("c1 CHECKED", "c1 SHADOW_STARTED")
        }

    @Test
    fun `a REQUIRED listener's full queue fails a gated step, and loses the event of a stored one - CTL-66`() =
        runBlocking<Unit> {
            val audit = Held("audit", Delivery.REQUIRED, cancellable = false)
            val changes = listeners(audit, requiredTimeout = 200.milliseconds, heap = heapFor(1))
            changes.publish(event(ChangeEvent.Kind.CHECKED))
            // A stored step's event finds no room in time: the step is not failed, the event is lost and counted.
            changes.publish(event(ChangeEvent.Kind.SHADOW_SKIPPED))
            changes.queues.single().lost.assert().isEqualTo(1L)
            // A gated step finds no room in time: the step fails, and its event is never recorded.
            runCatching { changes.publish(event(ChangeEvent.Kind.PUBLISHING)) }.exceptionOrNull()
                .assert().isInstanceOf(ChangeAuditException::class.java)
            audit.gate.complete(Unit)
            changes.awaitDelivered()
            // CHECKED's own call outlived its bound: a failure; SHADOW_SKIPPED was lost; PUBLISHING never queued.
            audit.received.assert().containsExactly("c1 CHECKED")
        }

    @Test
    fun `a gated event its step gave up on is not recorded afterwards - CTL-66`() = runBlocking<Unit> {
        val audit = Held("audit", Delivery.REQUIRED, cancellable = false)
        val changes = listeners(audit, requiredTimeout = 200.milliseconds)
        changes.publish(event(ChangeEvent.Kind.CHECKED))
        runCatching { changes.publish(event(ChangeEvent.Kind.PUBLISHING)) }.exceptionOrNull()
            .assert().isInstanceOf(ChangeAuditException::class.java)
        audit.gate.complete(Unit)
        changes.awaitDelivered()
        audit.received.assert().containsExactly("c1 CHECKED")
    }

    @Test
    fun `a listener that stops answering is reported, keeps its queue, and finds it when it answers again - CTL-66`() =
        runBlocking<Unit> {
            val listener = Held("kafka", Delivery.BEST_EFFORT)
            val changes = listeners(listener, requiredTimeout = 100.milliseconds)
            val queue = changes.queues.single()
            repeat(3) { changes.publish(event(ChangeEvent.Kind.CHECKED, "c$it")) }
            delay(300)
            queue.depth.assert().isEqualTo(3)
            queue.stalledFor.assert().isGreaterThan(100.milliseconds)
            queue.oldestAge.assert().isGreaterThan(100.milliseconds)
            queue.dropped.assert().isEqualTo(0L)
            launch { listener.gate.complete(Unit) }
            changes.awaitDelivered()
            listener.received.assert().containsExactly("c0 CHECKED", "c1 CHECKED", "c2 CHECKED")
            queue.bytes.assert().isEqualTo(0L)
            queue.stalledFor.assert().isEqualTo(Duration.ZERO)
        }

    @Test
    fun `a REQUIRED listener that does not answer one event is a failure, and the next is delivered - CTL-66`() =
        runBlocking<Unit> {
            val audit = Held("audit", Delivery.REQUIRED)
            val changes = listeners(audit, requiredTimeout = 100.milliseconds)
            changes.publish(event(ChangeEvent.Kind.CHECKED))
            delay(300)
            audit.gate.complete(Unit)
            changes.publish(event(ChangeEvent.Kind.PUBLISHING))
            changes.awaitDelivered()
            audit.received.assert().containsExactly("c1 PUBLISHING")
        }

    @Test
    fun `a REQUIRED listener's failed call loses a stored step's event, counted, and a gated one fails its step - CTL-66`() =
        runBlocking<Unit> {
            val failing = object : ChangeListener {
                override val name = "audit"
                override val delivery = Delivery.REQUIRED
                val received = CopyOnWriteArrayList<String>()

                override suspend fun onChange(event: ChangeEvent) {
                    if (event.note == "fail") error("the sink refused it")
                    if (event.note == "hang") delay(1_000)
                    received += "${event.change.value} ${event.kind}"
                }
            }
            val sloppy = object : ChangeListener {
                override val name = "kafka"
                override val delivery = Delivery.BEST_EFFORT

                override suspend fun onChange(event: ChangeEvent) {
                    if (event.note == "fail") error("the sink refused it")
                }
            }
            val changes = listeners(failing, sloppy, requiredTimeout = 200.milliseconds)
            val (audit, kafka) = changes.queues
            // A stored step's event that the REQUIRED listener refuses, then one it does not answer in time: both lost.
            changes.publish(event(ChangeEvent.Kind.CHECKED, note = "fail"))
            changes.publish(event(ChangeEvent.Kind.SHADOW_SKIPPED, note = "hang"))
            changes.publish(event(ChangeEvent.Kind.SHADOWED))
            changes.awaitDelivered()
            audit.lost.assert().isEqualTo(2L)
            failing.received.assert().containsExactly("c1 SHADOWED")
            // A BEST_EFFORT listener's failure promises nothing and loses nothing it promised.
            kafka.lost.assert().isEqualTo(0L)
            // A gated step's failure fails the step: nothing stored, so nothing lost.
            runCatching { changes.publish(event(ChangeEvent.Kind.PUBLISHING, note = "fail")) }.exceptionOrNull()
                .assert().isInstanceOf(ChangeAuditException::class.java)
            changes.awaitDelivered()
            audit.lost.assert().isEqualTo(2L)
        }

    /**
     * R3-12: the queue holds two small events, the first held. A larger event waits for room, then a smaller one
     * behind it. When the first is delivered, the room freed fits the smaller one only; it still waits for the larger
     * one, which was there first, so the queue keeps the order the events were put.
     */
    @Test
    fun `those who wait for room are served in the order they came, a smaller event never ahead - R3-12`() =
        runBlocking<Unit> {
            val audit = Held("audit", Delivery.REQUIRED, cancellable = false)
            val small = ChangeEventSize.of(event(ChangeEvent.Kind.CHECKED, "s1"))
            val heap = ChangeListeners.HEAP_SHARE * 2 * small
            val changes = listeners(audit, heap = heap)
            changes.publish(event(ChangeEvent.Kind.CHECKED, "s1"))
            changes.publish(event(ChangeEvent.Kind.CHECKED, "s2"))
            // Half as large again as a small one: it fits only once both small ones are delivered.
            val large = event(ChangeEvent.Kind.CHECKED, "big", note = "x".repeat((small / 4).toInt()))
            ChangeEventSize.of(large).assert().isGreaterThan(small).isLessThanOrEqualTo(2 * small)
            val first = async(Dispatchers.Default) { changes.publish(large) }
            delay(100)
            val second = async(Dispatchers.Default) { changes.publish(event(ChangeEvent.Kind.CHECKED, "s3")) }
            delay(100)
            first.isCompleted.assert().isFalse()
            second.isCompleted.assert().isFalse()
            audit.gate.complete(Unit)
            first.await()
            second.await()
            changes.awaitDelivered()
            audit.received.assert().containsExactly("s1 CHECKED", "s2 CHECKED", "big CHECKED", "s3 CHECKED")
        }

    @Test
    fun `an event is measured from what it holds, never by encoding it - CTL-66`() {
        val small = ChangeEventSize.of(event(ChangeEvent.Kind.CHECKED))
        val noted = ChangeEventSize.of(event(ChangeEvent.Kind.CHECKED, note = "x".repeat(1000)))
        (noted - small).assert().isEqualTo(2000L)
    }
}
