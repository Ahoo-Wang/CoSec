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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Review M3: a change that takes a busy slot (RC1-12) makes its holder `CONFLICTED` before the slot moves, from
 * whatever staged state the holder reached meanwhile; and a publish goes on only while its change still holds the slot.
 * Each race runs at a store read, through a store that runs it once there.
 */
class PreemptionRaceTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val racing = Racing(memory)

    /** A harness over the racing store, with a platform document to narrow: preemption narrows a head. */
    private suspend fun harness(): ChangeHarness {
        val harness = ChangeHarness(clock, memory, racing, ChangeOptions(shadow = IMMEDIATE_WINDOW))
        val setup = harness.changes.propose(
            PLATO,
            SnapshotKey.Platform,
            0,
            listOf(Operation.PutPolicy(allow("reads"))),
            "setup"
        )
        harness.changes.skipShadow(PLATO, setup.id, "setup")
        harness.changes.publish(PLATO, setup.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        return harness
    }

    /** [harness]'s documents under four-eyes, where every platform change waits for a second person. */
    private fun fourEyes(harness: ChangeHarness) = Changes(
        racing,
        harness.revisions,
        { ChangeId("f${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW, fourEyes = true),
    )

    private val ids = java.util.concurrent.atomic.AtomicInteger()

    /** A ban: a new DENY and its binding, which narrows and so takes a busy slot. */
    private val ban = listOf(
        Operation.PutPolicy(
            PolicyDefinition(
                PolicyId("ban"),
                false,
                null,
                listOf(StatementSource("", Effect.DENY, listOf("order:delete")))
            )
        ),
        Operation.PutBinding(
            Binding(BindingId("ban"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId("ban"))
        ),
    )

    private suspend fun head(harness: ChangeHarness): Long = harness.revisions.head(SnapshotKey.Platform)!!.revision

    /** A platform change in its shadow window, holding the slot. */
    private suspend fun holding(harness: ChangeHarness): ChangeRequest {
        val change = harness.changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head(harness),
            listOf(Operation.PutPolicy(allow("slow"))),
            "slow"
        )
        return harness.changes.stage(PLATO, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }
    }

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    @Test
    fun `a holder approved while it is preempted is conflicted`() = runBlocking<Unit> {
        val harness = harness()
        val changes = fourEyes(harness)
        val proposed = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head(harness),
            listOf(Operation.PutPolicy(allow("slow"))),
            "slow"
        )
        val held = changes.stage(PLATO, proposed.id)
        val waiting = changes.shadowed(held.id)
        waiting.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        val emergency = changes.propose(PLATO, SnapshotKey.Platform, head(harness), ban, "ban")
        // The preempting change reads the holder awaiting approval; another instance approves it right after.
        racing.afterChange(held.id) {
            val current = memory.change(held.id)!!
            val approval = Approval(
                PIA.actor,
                clock.now,
                waiting.candidate!!,
                waiting.escalations.map { it.subject },
                NO_MEMBERSHIPS,
                credentialId = "token-pia",
            )
            memory.putChange(current.digest, current.value.copy(state = ChangeState.READY, approval = approval))
                .assert().isTrue()
        }
        changes.skipShadow(PLATO, emergency.id, "incident")
        changes.get(held.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        refused { changes.publish(PLATO, held.id) }.assert().isEqualTo(409)
        memory.stage(SnapshotKey.Platform)!!.value.holder.assert().isEqualTo(emergency.id)
    }

    @Test
    fun `a holder whose window closes while it is preempted is conflicted`() = runBlocking<Unit> {
        val harness = harness()
        val held = holding(harness)
        val emergency = harness.changes.propose(PLATO, SnapshotKey.Platform, head(harness), ban, "ban")
        // The preempting change reads the holder shadowing; its window closes right after.
        racing.afterChange(held.id) {
            harness.changes.shadowed(held.id).state.assert().isEqualTo(ChangeState.READY)
        }
        harness.changes.skipShadow(PLATO, emergency.id, "incident").state.assert().isEqualTo(ChangeState.READY)
        harness.changes.get(held.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        refused { harness.changes.publish(PLATO, held.id) }.assert().isEqualTo(409)
        harness.changes.publish(PLATO, emergency.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `a change preempted while it publishes publishes nothing`() = runBlocking<Unit> {
        val harness = harness()
        val held = holding(harness)
        harness.changes.shadowed(held.id).state.assert().isEqualTo(ChangeState.READY)
        val emergency = harness.changes.propose(PLATO, SnapshotKey.Platform, head(harness), ban, "ban")
        // The holder's publish has read it READY when the ban takes its slot.
        racing.beforeHead {
            harness.changes.skipShadow(PLATO, emergency.id, "incident").state.assert().isEqualTo(ChangeState.READY)
        }
        refused { harness.changes.publish(PLATO, held.id) }.assert().isEqualTo(409)
        harness.changes.get(held.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        harness.revisions.head(SnapshotKey.Platform)!!.revision.assert().isEqualTo(1)
        harness.changes.publish(PLATO, emergency.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    /** [harness]'s documents with a REQUIRED listener that records every change event in [events]. */
    private fun listened(harness: ChangeHarness, events: MutableList<ChangeEvent>) = Changes(
        racing,
        harness.revisions,
        { ChangeId("l${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        listeners = ChangeListeners(
            listOf(
                object : ChangeListener {
                    override val name = "recorder"
                    override val delivery = Delivery.REQUIRED

                    override suspend fun onChange(event: ChangeEvent) {
                        events += event
                    }
                }
            )
        ).also { opened += it },
    )

    private val opened = ArrayList<ChangeListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    /** Events that do not gate a step reach a listener on its own queue: the one of [change] with [note] once there. */
    private fun note(events: List<ChangeEvent>, change: ChangeId, kind: ChangeEvent.Kind): String? {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (events.none { it.change == change && it.kind == kind }) {
            check(System.nanoTime() < deadline) { "Not delivered: ${events.map { it.kind }}" }
            Thread.sleep(10)
        }
        return events.single { it.change == change && it.kind == kind }.note
    }

    /**
     * Review L2: a change preempted after its first slot check, while its PUBLISHING record is written, finds the slot
     * gone right before its head write: nothing is written, and its publish is recorded as failed, saying why.
     */
    @Test
    fun `a change preempted before its head write writes nothing`() = runBlocking<Unit> {
        val harness = harness()
        val events = java.util.concurrent.CopyOnWriteArrayList<ChangeEvent>()
        val changes = listened(harness, events)
        val proposed = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head(harness),
            listOf(Operation.PutPolicy(allow("slow"))),
            "slow"
        )
        changes.skipShadow(PLATO, proposed.id, "test").state.assert().isEqualTo(ChangeState.READY)
        val emergency = changes.propose(PLATO, SnapshotKey.Platform, head(harness), ban, "ban")
        // The holder's publish has checked the slot once when the ban takes it.
        racing.afterStage(skip = 0) {
            changes.skipShadow(PLATO, emergency.id, "incident").state.assert().isEqualTo(ChangeState.READY)
        }
        changes.publish(PLATO, proposed.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        harness.revisions.head(SnapshotKey.Platform)!!.revision.assert().isEqualTo(1)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (events.count { it.change == proposed.id && it.kind == ChangeEvent.Kind.FAILED } < 2) {
            check(System.nanoTime() < deadline) { "Not delivered: ${events.map { it.kind }}" }
            Thread.sleep(10)
        }
        events.filter { it.change == proposed.id && it.kind == ChangeEvent.Kind.FAILED }.map { it.note }.assert()
            .contains(
                "preempted by ${emergency.id.value}",
                "another change took the staging slot before the head write"
            )
        changes.publish(PLATO, emergency.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    /**
     * Review L2: within the one round trip between the last slot check and the head write, the preempted change still
     * publishes; the change that took its slot then conflicts, and its failure says which change published first.
     */
    @Test
    fun `the change that took a slot records the publish that beat it`() = runBlocking<Unit> {
        val harness = harness()
        val events = java.util.concurrent.CopyOnWriteArrayList<ChangeEvent>()
        val changes = listened(harness, events)
        val proposed = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head(harness),
            listOf(Operation.PutPolicy(allow("slow"))),
            "slow"
        )
        changes.skipShadow(PLATO, proposed.id, "test").state.assert().isEqualTo(ChangeState.READY)
        val emergency = changes.propose(PLATO, SnapshotKey.Platform, head(harness), ban, "ban")
        // The holder's last slot check, right before its head write, has passed when the ban takes the slot.
        racing.afterStage(skip = 1) {
            changes.skipShadow(PLATO, emergency.id, "incident").state.assert().isEqualTo(ChangeState.READY)
        }
        changes.publish(PLATO, proposed.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        changes.publish(PLATO, emergency.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        note(events, emergency.id, ChangeEvent.Kind.FAILED).assert().isEqualTo(
            "change ${proposed.id.value}, which held the slot before it, published over its base first (revision 2)"
        )
    }

    /** Review L5: a holder made CONFLICTED for a change that then does not take the slot is recorded as such. */
    @Test
    fun `a preemption that gives up says so`() = runBlocking<Unit> {
        val harness = harness()
        val events = java.util.concurrent.CopyOnWriteArrayList<ChangeEvent>()
        val changes = listened(harness, events)
        val proposed = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head(harness),
            listOf(Operation.PutPolicy(allow("slow"))),
            "slow"
        )
        val held = changes.stage(PLATO, proposed.id)
        val emergency = changes.propose(PLATO, SnapshotKey.Platform, head(harness), ban, "ban")
        racing.refuseStages = true
        refused { changes.skipShadow(PLATO, emergency.id, "incident") }.assert().isEqualTo(409)
        racing.refuseStages = false
        changes.get(held.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        note(events, held.id, ChangeEvent.Kind.FAILED)
            .assert().isEqualTo("conflicted by ${emergency.id.value}, which did not take the slot")
    }

    /** Runs a race once: after a read of one change, or before a read of a head. */
    private class Racing(private val memory: InMemoryControlStore) : ControlStore by memory {
        /** Whether every write of a staging slot is refused, as if it kept changing. */
        @Volatile
        var refuseStages = false

        @Volatile
        private var stage: Pair<Int, suspend () -> Unit>? = null

        /** Runs [race] once, right after the read of a staging slot that follows [skip] others. */
        fun afterStage(skip: Int, race: suspend () -> Unit) {
            stage = skip to race
        }

        override suspend fun stage(key: SnapshotKey): Versioned<StageSlot>? {
            val read = memory.stage(key)
            stage?.let { (skip, race) ->
                if (skip > 0) {
                    stage = skip - 1 to race
                } else {
                    stage = null
                    race()
                }
            }
            return read
        }

        override suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean =
            !refuseStages && memory.putStage(key, expected, slot)

        @Volatile
        private var change: Pair<ChangeId, suspend () -> Unit>? = null

        @Volatile
        private var head: (suspend () -> Unit)? = null

        fun afterChange(id: ChangeId, race: suspend () -> Unit) {
            change = id to race
        }

        fun beforeHead(race: suspend () -> Unit) {
            head = race
        }

        override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? {
            val read = memory.change(id)
            change?.takeIf { it.first == id }?.let { (_, race) ->
                change = null
                race()
            }
            return read
        }

        override suspend fun head(key: SnapshotKey): StoredDocument? {
            head?.let { race ->
                head = null
                race()
            }
            return memory.head(key)
        }
    }

    private companion object {
        val PIA = Operator(Actor(TenantId.PLATFORM, PrincipalId("pia")), NO_MEMBERSHIPS)
    }
}
