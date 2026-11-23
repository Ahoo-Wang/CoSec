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
@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `CTL-86` (DR1-F-7): a head a failover moved back under its index, one revision ahead, is found by the reclamation
 * pass, logged, counted and told to the change listeners once, naming the change the lost revision's provenance names.
 */
class LostHeadsTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val events = CopyOnWriteArrayList<ChangeEvent>()
    private val told = CompletableDeferred<ChangeEvent>()
    private val listeners = ChangeListeners(
        listOf(
            object : ChangeListener {
                override val name = "recorder"

                override suspend fun onChange(event: ChangeEvent) {
                    events += event
                    told.complete(event)
                }
            },
        ),
    )
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
        listeners = listeners,
    )
    private val maintenance = ControlMaintenance(store, revisions, changes, Duration.ofMinutes(1))

    private suspend fun publish(change: String) {
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        revisions.publish(ACME, revisions.head(ACME), candidate(ACME), publication(change))
    }

    /** The head moved back to [revision], the index and the records as they were: what a failover leaves. */
    private suspend fun headBackTo(revision: Long) {
        val entry = store.index(ACME)!!.value.entries.first { it.revision == revision }
        store.putHead(ACME, store.head(ACME)!!.digest, store.record(ACME, entry.digest)!!)
    }

    @Test
    fun `a head moved back under its index is told once, naming the lost revision's change`() = runBlocking<Unit> {
        publish("first")
        publish("second")
        publish("lost")
        headBackTo(2)
        store.lostRevisions(ACME).map { it.revision }.assert().containsExactly(3L)

        maintenance.reclaim()
        val event = withTimeout(EVENT_DELIVERED.toMillis()) { told.await() }

        event.kind.assert().isEqualTo(ChangeEvent.Kind.HEAD_LOST)
        event.change.value.assert().isEqualTo("lost")
        event.document.assert().isEqualTo(ACME)
        event.base!!.revision.assert().isEqualTo(2L)
        event.candidate!!.revision.assert().isEqualTo(3L)
        event.operator.assert().isEqualTo(StoreFixtures.ALICE)
        maintenance.lostRevisions.assert().isEqualTo(1L)

        maintenance.reclaim()
        maintenance.lostRevisions.assert().isEqualTo(1L)
        events.count { it.kind == ChangeEvent.Kind.HEAD_LOST }.assert().isEqualTo(1)
    }

    @Test
    fun `a head on its index's last revision loses nothing`() = runBlocking<Unit> {
        publish("first")
        publish("second")

        maintenance.reclaim()

        store.lostRevisions(ACME).assert().isEmpty()
        maintenance.lostRevisions.assert().isEqualTo(0L)
    }

    private companion object {
        /** How long a best-effort listener may take to receive an event its pass told. */
        val EVENT_DELIVERED: Duration = Duration.ofSeconds(10)
    }
}
