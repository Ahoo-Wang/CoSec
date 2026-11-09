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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * #1227: the change that is abandoned withdraws only its own candidate. Its withdrawal runs after it is terminal, so
 * another change may have taken the slot and staged its own candidate meanwhile; that one stays. The interleaving is
 * set by latches on the store, not by timing.
 */
class CandidateWithdrawalRaceTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** Holds the first withdrawal once [armed], until [resume]. */
    private val store = object : ControlStore by memory {
        var armed = false
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean {
            if (armed) {
                armed = false
                paused.complete(Unit)
                resume.await()
            }
            return memory.withdrawCandidate(key, window)
        }
    }
    private val harness = ChangeHarness(clock, memory, store)
    private val changes = harness.changes

    @Test
    fun `an abandoned change's late withdrawal leaves the candidate of the change that took its slot - #1227`() =
        runBlocking<Unit> {
            harness.revisions.publish(ACME, null, StoreFixtures.candidate(ACME), StoreFixtures.publication())
            val operations = listOf(Operation.PutPolicy(allow("read")))
            val first = changes.propose(ALICE_OP, ACME, 1, operations, "first")
            changes.stage(ALICE_OP, first.id).state.assert().isEqualTo(ChangeState.SHADOWING)
            val second = changes.propose(ALICE_OP, ACME, 1, operations, "second")

            store.armed = true
            val abandoning = async { changes.abandon(ALICE_OP, first.id) }
            store.paused.await()
            changes.get(first.id).state.assert().isEqualTo(ChangeState.ABANDONED)
            val staged = changes.stage(ALICE_OP, second.id)
            staged.state.assert().isEqualTo(ChangeState.SHADOWING)
            store.resume.complete(Unit)
            abandoning.await().state.assert().isEqualTo(ChangeState.ABANDONED)

            memory.stage(ACME)!!.value.holder.assert().isEqualTo(second.id)
            memory.candidate(ACME)?.window.assert().isEqualTo(staged.shadow!!.window)
        }

    @Test
    fun `a withdrawal that is not late removes the change's own candidate`() = runBlocking<Unit> {
        harness.revisions.publish(ACME, null, StoreFixtures.candidate(ACME), StoreFixtures.publication())
        val change = changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow("read"))), "only")
        changes.stage(ALICE_OP, change.id)
        memory.candidate(ACME).assert().isNotNull()
        changes.abandon(ALICE_OP, change.id)
        memory.candidate(ACME).assert().isNull()
    }
}
