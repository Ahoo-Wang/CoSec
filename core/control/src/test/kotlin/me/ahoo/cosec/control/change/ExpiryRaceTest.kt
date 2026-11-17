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
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * #1545: a read that finds a change past its expiry ends it, and a reader that lost the race for it answers what the
 * change is now, never a 409 for a read. Each interleaving runs at the first store read of the change, through a store
 * that lets another caller finish there, and the commands keep their own guards.
 */
class ExpiryRaceTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val racing = Racing(memory)
    private val harness = ChangeHarness(clock, memory, racing)

    /** A checked change, past its expiry by a second. */
    private suspend fun expiredChange(): ChangeRequest {
        val change = harness.changes.propose(ALICE_OP, ACME, 0, emptyList(), "nothing")
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        clock.now = clock.now.plus(ChangeOptions().expireAfter).plusSeconds(1)
        return change
    }

    @Test
    fun `two readers of a change that has just expired both read it expired`() = runBlocking<Unit> {
        val change = expiredChange()
        var other: ChangeRequest? = null
        racing.afterRead(change.id) { other = harness.changes.visible(ALICE_OP, change.id) }

        harness.changes.visible(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.EXPIRED)

        other!!.state.assert().isEqualTo(ChangeState.EXPIRED)
        memory.change(change.id)!!.value.state.assert().isEqualTo(ChangeState.EXPIRED)
        harness.changes.visible(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.EXPIRED)
    }

    @Test
    fun `a reader that lost the race to another terminal state answers the record as it is`() = runBlocking<Unit> {
        val change = expiredChange()
        racing.afterRead(
            change.id
        ) { memory.putChange(memory.change(change.id)!!.digest, change.copy(state = ChangeState.ABANDONED)) }

        harness.changes.visible(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        memory.change(change.id)!!.value.state.assert().isEqualTo(ChangeState.ABANDONED)
    }

    @Test
    fun `a change that moved to another open state meanwhile is expired from where it is`() = runBlocking<Unit> {
        val change = expiredChange()
        racing.afterRead(
            change.id
        ) { memory.putChange(memory.change(change.id)!!.digest, change.copy(state = ChangeState.READY)) }

        harness.changes.visible(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.EXPIRED)
        memory.change(change.id)!!.value.state.assert().isEqualTo(ChangeState.EXPIRED)
    }

    @Test
    fun `a pass that listed the change before another reader expired it reads it expired`() = runBlocking<Unit> {
        val change = expiredChange()
        harness.changes.visible(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.EXPIRED)

        // The copy a maintenance pass holds from listing the open changes, still CHECKED.
        harness.changes.current(change).state.assert().isEqualTo(ChangeState.EXPIRED)
    }

    @Test
    fun `a change that keeps changing under the read is a 409 after the attempts, never a guess`() = runBlocking<Unit> {
        val change = expiredChange()
        racing.refuseChangeWrites = true

        assertThrows<ChangeRejectedException> { harness.changes.visible(ALICE_OP, change.id) }
            .status.assert().isEqualTo(ControlStatus.CONFLICT)
        racing.refuseChangeWrites = false
        harness.changes.visible(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.EXPIRED)
    }

    @Test
    fun `a command still refuses an expired change with a 409 of its own`() = runBlocking<Unit> {
        val change = expiredChange()
        harness.changes.visible(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.EXPIRED)

        assertThrows<ChangeRejectedException> { harness.changes.rebase(ALICE_OP, change.id) }
            .status.assert().isEqualTo(ControlStatus.CONFLICT)
    }

    /** Runs a race once, right after the first read of a change. */
    private class Racing(private val memory: InMemoryControlStore) : ControlStore by memory {
        @Volatile
        private var race: Pair<ChangeId, suspend () -> Unit>? = null

        /** Whether every write of a change is refused, as if another caller kept winning it. */
        @Volatile
        var refuseChangeWrites = false

        override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean =
            !refuseChangeWrites && memory.putChange(expected, change)

        fun afterRead(id: ChangeId, block: suspend () -> Unit) {
            race = id to block
        }

        override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? {
            val read = memory.change(id)
            race?.takeIf { it.first == id }?.let { (_, block) ->
                race = null
                block()
            }
            return read
        }
    }
}
