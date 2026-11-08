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
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * A document's staging slot as [Changes] keeps it ([StagingSlots]): releasing it and withdrawing its candidate never
 * fail the step that does it when the store fails, cancellation aside; a slot that keeps changing, or that another
 * change holds, is left as it is.
 */
class StagingSlotsTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** What the next candidate writes throw, while set. */
    @Volatile
    private var failing: Exception? = null

    /** Whether every slot write is refused, as if the slot kept changing. */
    @Volatile
    private var refuseStages = false
    private val store = object : ControlStore by memory {
        override suspend fun putCandidate(key: SnapshotKey, candidate: StoredCandidate) {
            failing?.let { throw it }
            memory.putCandidate(key, candidate)
        }

        override suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean {
            failing?.let { throw it }
            return memory.withdrawCandidate(key, window)
        }

        override suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean =
            !refuseStages && memory.putStage(key, expected, slot)
    }
    private val options = ChangeOptions(shadow = IMMEDIATE_WINDOW)
    private val harness = ChangeHarness(clock, memory, store, options)
    private val slots = StagingSlots(store, clock, options)

    /** A change of acme's document in its shadow window: it holds the slot, and its candidate is staged. */
    private suspend fun shadowing(): ChangeRequest {
        val change = harness.changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("a"))), "a")
        return harness.changes.stage(ALICE_OP, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }
    }

    @Test
    fun `a candidate that cannot be withdrawn stays to compare`() = runBlocking<Unit> {
        val change = shadowing()
        failing = IllegalStateException("the store is down")
        slots.withdraw(change)
        memory.candidate(ACME)!!.window.assert().isEqualTo(change.shadow!!.window)
        failing = CancellationException("the caller went away")
        assertThrownBy<CancellationException> { runBlocking { slots.withdraw(change) } }
        failing = null
        slots.withdraw(change)
        memory.candidate(ACME).assert().isNull()
    }

    @Test
    fun `a slot is released only by its holder, and left when it keeps changing`() = runBlocking<Unit> {
        val change = shadowing()
        slots.holds(change).assert().isTrue()
        slots.release(change.copy(id = ChangeId("other")))
        slots.holds(change).assert().isTrue()
        refuseStages = true
        slots.release(change)
        slots.holds(change).assert().isTrue()
        refuseStages = false
        slots.release(change)
        slots.holds(change).assert().isFalse()
        memory.candidate(ACME).assert().isNull()
        slots.release(change)
    }

    @Test
    fun `a holder stages until it expires`() = runBlocking<Unit> {
        val change = shadowing()
        slots.stillStaged(change).assert().isTrue()
        slots.stillStaged(change.copy(state = ChangeState.PUBLISHED)).assert().isFalse()
        clock.now = clock.now.plus(options.expireAfter).plus(Duration.ofSeconds(1))
        slots.stillStaged(change).assert().isFalse()
    }
}
