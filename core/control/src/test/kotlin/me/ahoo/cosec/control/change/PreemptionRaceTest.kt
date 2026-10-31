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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
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
        // The preempting change reads the holder awaiting approval; it is approved right after.
        racing.afterChange(held.id) {
            changes.approve(PIA, held.id, waiting.candidate!!).state.assert().isEqualTo(ChangeState.READY)
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

    /** Runs a race once: after a read of one change, or before a read of a head. */
    private class Racing(private val memory: InMemoryControlStore) : ControlStore by memory {
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
