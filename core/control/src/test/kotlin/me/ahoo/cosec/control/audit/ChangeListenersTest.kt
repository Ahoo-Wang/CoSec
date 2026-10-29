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

package me.ahoo.cosec.control.audit

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
