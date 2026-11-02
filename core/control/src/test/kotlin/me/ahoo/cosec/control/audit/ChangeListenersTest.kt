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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RFC 0007 §10.5, Q21: gated events wait for every REQUIRED listener; nothing else waits, and failures are logged. */
class ChangeListenersTest {
    private val opened = ArrayList<ChangeListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    private class Recording(
        override val name: String,
        override val delivery: Delivery,
        private val action: suspend () -> Unit = {},
    ) : ChangeListener {
        val events = CopyOnWriteArrayList<ChangeEvent>()

        override suspend fun onChange(event: ChangeEvent) {
            action()
            events += event
        }
    }

    private fun event(kind: ChangeEvent.Kind) = ChangeEvent(
        "e",
        Instant.EPOCH,
        kind,
        Actor(TenantId.PLATFORM, PrincipalId("plato")),
        ChangeId("c1"),
        SnapshotKey.Platform,
        RevisionRef(1, "d"),
        null,
    )

    /**
     * Only a test that waits for a listener to time out needs a short [requiredTimeout]: under it, a cold JVM's first
     * call to a listener that answers at once could miss it.
     */
    private fun listeners(vararg listeners: ChangeListener, requiredTimeout: Duration = 5.seconds) =
        ChangeListeners(listeners.toList(), requiredTimeout = requiredTimeout).also { opened += it }

    private fun <T> eventually(read: () -> List<T>, count: Int = 1): List<T> = runBlocking {
        withTimeout(5.seconds) {
            while (read().size < count) delay(5)
        }
        read()
    }

    @Test
    fun `a gated event waits for the REQUIRED listeners, and fails with them`() = runBlocking<Unit> {
        val required = Recording("required", Delivery.REQUIRED)
        val bestEffort = Recording("best-effort", Delivery.BEST_EFFORT)
        val registered = listeners(required, bestEffort)
        registered.hasRequired.assert().isTrue()
        registered.publish(event(ChangeEvent.Kind.PUBLISHING))
        required.events.single().kind.assert().isEqualTo(ChangeEvent.Kind.PUBLISHING)
        eventually({ bestEffort.events })

        val failing = listeners(Recording("failing", Delivery.REQUIRED) { error("down") })
        assertThrownBy<ChangeAuditException> {
            runBlocking {
                failing.publish(
                    event(ChangeEvent.Kind.BREAK_GLASS_REQUESTED)
                )
            }
        }
        val hanging = listeners(
            Recording("hanging", Delivery.REQUIRED) { awaitCancellation() },
            requiredTimeout = 100.milliseconds,
        )
        assertThrownBy<ChangeAuditException> {
            runBlocking {
                hanging.publish(
                    event(ChangeEvent.Kind.BREAK_GLASS_APPROVED)
                )
            }
        }
    }

    /**
     * RC1-31: a listener that throws a cancellation while the step is active (a cancelled future, say) fails like any
     * other, so the step answers 503; only the caller's own cancellation is rethrown.
     */
    @Test
    fun `a listener's own cancellation is a failure of that listener`() = runBlocking<Unit> {
        val cancelling = listeners(
            Recording("cancelling", Delivery.REQUIRED) { throw CancellationException("the future was cancelled") }
        )
        val failure = try {
            cancelling.publish(event(ChangeEvent.Kind.PUBLISHING))
            null
        } catch (e: ChangeAuditException) {
            e
        }
        failure.assert().isNotNull()
        failure!!.cause.assert().isInstanceOf(CancellationException::class.java)

        val caller = launch {
            listeners(Recording("waiting", Delivery.REQUIRED) { awaitCancellation() }).publish(
                event(ChangeEvent.Kind.PUBLISHING)
            )
        }
        delay(50)
        caller.cancel()
        caller.join()
        caller.isCancelled.assert().isTrue()
    }

    @Test
    fun `other events wait for nobody, and a failing listener costs only its own record`() = runBlocking<Unit> {
        val failing = Recording("failing", Delivery.REQUIRED) { error("down") }
        val logging = LoggingChangeListener()
        val recording = Recording("recording", Delivery.BEST_EFFORT)
        val registered = listeners(failing, logging, recording)
        registered.publish(event(ChangeEvent.Kind.PUBLISHED))
        registered.publish(event(ChangeEvent.Kind.CHECKED))
        eventually({ recording.events }, 2)
        ChangeEvent.Kind.entries.filter { it.gated }.assert().containsExactlyInAnyOrder(
            ChangeEvent.Kind.PUBLISHING,
            ChangeEvent.Kind.BREAK_GLASS_REQUESTED,
            ChangeEvent.Kind.BREAK_GLASS_APPROVED,
        )
        ChangeListeners.NONE.hasRequired.assert().isFalse()
        ChangeListeners.NONE.publish(event(ChangeEvent.Kind.PUBLISHING))
        assertThrownBy<IllegalArgumentException> {
            ChangeListeners(
                listOf(recording, Recording("recording", Delivery.REQUIRED))
            )
        }
        event(ChangeEvent.Kind.PUBLISHED).toString().assert().contains("c1")
    }

    /** RC1-33: a moved-heads event names each head, as checked and as it is now, and whether it could be read. */
    @Test
    fun `a moved-heads event writes the heads that moved`() {
        val event = ChangeEvent(
            "e",
            Instant.EPOCH,
            ChangeEvent.Kind.REVIEW_NEEDED,
            Actor(TenantId("acme"), PrincipalId("alice")),
            ChangeId("c1"),
            SnapshotKey.Tenant("acme"),
            RevisionRef(1, "d"),
            RevisionRef(2, "e"),
            note = "the analysis on the current heads finds 1 escalation(s) the publish did not have approved",
            moved = listOf(
                MovedHead("platform", "a", "b"),
                MovedHead("app:order", "c", null, readable = false),
            ),
        )
        val json = me.ahoo.cosec.runtime.json.CoSecJson.mapper.readTree(ChangeEventJson.write(event))
        json.get("kind").asString().assert().isEqualTo("REVIEW_NEEDED")
        json.get("moved").values().map {
            "${it.get("document").asString()} ${it.get("checked").asString()} ${it.get("readable").asBoolean()}"
        }
            .assert().containsExactly("platform a true", "app:order c false")
        json.get("moved").get(1).get("current").isNull.assert().isTrue()
        // Other events have no moved member.
        val plain = me.ahoo.cosec.runtime.json.CoSecJson.mapper.readTree(
            ChangeEventJson.write(event(ChangeEvent.Kind.PUBLISHED))
        )
        plain.has("moved").assert().isFalse()
    }
}
