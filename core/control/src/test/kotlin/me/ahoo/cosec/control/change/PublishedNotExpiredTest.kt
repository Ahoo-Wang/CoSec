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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * DR1-E-7: a change whose head write went through and whose settling failed (the store refusing the `PUBLISHED` record
 * as moved, here every time, as another writer that keeps moving it) is published: its revision decides. Its publish
 * answers `503` "written; publishing it again settles it", not a `409` the caller would read as nothing done, and when
 * its expiry comes it is settled `PUBLISHED`, never recorded `EXPIRED` while its revision is in effect.
 *
 * The real in-memory store; a gate, not a pause, makes the interleaving: while it is closed the conditional write of the
 * change's `PUBLISHED` record answers "moved", after the head is written.
 */
class PublishedNotExpiredTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** While true, the change's `PUBLISHED` record does not land: its conditional write answers "moved". */
    @Volatile
    private var settlingRefused = false

    private val store = object : ControlStore by memory {
        override suspend fun putChange(
            expected: SnapshotDigest?,
            change: ChangeRequest,
            previous: ChangeState?
        ): Boolean {
            if (settlingRefused && change.state == ChangeState.PUBLISHED) return false
            return memory.putChange(expected, change, previous)
        }
    }
    private val revisions = Revisions(store, clock)
    private val events = CopyOnWriteArrayList<ChangeEvent>()
    private val listeners = ChangeListeners(
        listOf(
            object : ChangeListener {
                override val name = "recorder"
                override val delivery = Delivery.REQUIRED

                override suspend fun onChange(event: ChangeEvent) {
                    events += event
                }
            }
        )
    )
    private val ids = AtomicInteger()
    private val options = ChangeOptions(shadow = IMMEDIATE_WINDOW)
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        options,
        listeners = listeners,
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
    )

    /** A change READY to publish on [ACME]. */
    private suspend fun ready(policy: String): ChangeRequest {
        val change = changes.propose(
            PLATO,
            ACME,
            revisions.head(ACME)?.revision ?: 0,
            listOf(Operation.PutPolicy(allow(policy))),
            policy
        )
        return changes.skipShadow(PLATO, change.id, "test").also { it.state.assert().isEqualTo(ChangeState.READY) }
    }

    private fun pastExpiry() {
        clock.now = clock.now.plus(options.expireAfter).plus(Duration.ofSeconds(1))
    }

    @Test
    fun `a publish whose settling kept conflicting after the head write answers 503, written`() = runBlocking<Unit> {
        val change = ready("reads")
        settlingRefused = true

        val failure = assertThrows<ChangeRejectedException> { changes.publish(PLATO, change.id) }

        failure.status.assert().isEqualTo(503)
        failure.message!!.assert().contains("revision 1 is written").contains("publishing it again settles it")
        revisions.head(ACME)!!.revision.assert().isEqualTo(1L)
        changes.get(change.id).state.assert().isEqualTo(ChangeState.READY)
        listeners.cancel()
    }

    @Test
    fun `a change whose own revision is in effect is settled published when its expiry comes, never expired`() = runBlocking<Unit> {
        val change = ready("reads")
        settlingRefused = true
        assertThrows<ChangeRejectedException> { changes.publish(PLATO, change.id) }
        settlingRefused = false
        listeners.awaitDelivered()
        events.clear()
        pastExpiry()

        val read = changes.get(change.id)

        read.state.assert().isEqualTo(ChangeState.PUBLISHED)
        read.publishedRevision.assert().isEqualTo(1L)
        store.stage(ACME)?.value?.holder.assert().isNull()
        listeners.awaitDelivered()
        events.single { it.kind == ChangeEvent.Kind.PUBLISHED }.operator.assert().isEqualTo(PLATO.actor)
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L)
        listeners.cancel()
    }

    @Test
    fun `a change no revision of which is in effect still expires`() = runBlocking<Unit> {
        val change = ready("reads")
        pastExpiry()

        changes.get(change.id).state.assert().isEqualTo(ChangeState.EXPIRED)
        revisions.head(ACME).assert().isNull()
        listeners.cancel()
    }

    /** A change an earlier build recorded `EXPIRED` while its revision was in effect: publishing it settles it. */
    @Test
    fun `a change recorded expired while its revision was in effect is settled by publishing it`() = runBlocking<Unit> {
        val change = ready("reads")
        settlingRefused = true
        assertThrows<ChangeRejectedException> { changes.publish(PLATO, change.id) }
        settlingRefused = false
        val stored = memory.change(change.id)!!
        memory.putChange(
            stored.digest,
            stored.value.copy(state = ChangeState.EXPIRED),
            stored.value.state
        ).assert().isTrue()

        val published = changes.publish(PLATO, change.id)

        published.state.assert().isEqualTo(ChangeState.PUBLISHED)
        published.publishedRevision.assert().isEqualTo(1L)
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L)
        listeners.cancel()
    }
}
