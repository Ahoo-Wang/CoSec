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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class)

package me.ahoo.cosec.control.change

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * #1235 (`CTL-6`): whoever holds a document's staging slot is in a staged state from the moment it holds it, so a call
 * still working on its candidate cannot be taken for a slot nobody uses. What it returns as `SHADOWING` is the holder of
 * the slot and the owner of the candidate; a stage that fails or is cancelled gives both back. The racing call runs at a
 * store write through a store that holds it there, over the real in-memory store.
 */
class StageReservationTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    private class Racing(private val memory: InMemoryControlStore) : ControlStore by memory {
        /** What runs once right before the next candidate write: another call meanwhile. */
        @Volatile
        var beforeCandidate: (suspend () -> Unit)? = null

        /** Whether the next candidate write waits for [resume], having told [paused]. */
        @Volatile
        var holdCandidate = false
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        /** What the next candidate writes throw instead of writing, while set. */
        @Volatile
        var failCandidate: Exception? = null

        /** Whether the next candidate write goes through and then fails, as a reply lost on the way back. */
        @Volatile
        var loseCandidateReply = false

        /** What runs once right before the next slot write: another call meanwhile. */
        @Volatile
        var beforeSlot: (suspend () -> Unit)? = null

        /** What runs once right after the next slot write that went through: another call meanwhile. */
        @Volatile
        var afterSlot: (suspend () -> Unit)? = null

        override suspend fun putCandidate(
            key: SnapshotKey,
            expected: SnapshotDigest?,
            candidate: StoredCandidate
        ): Boolean {
            beforeCandidate?.let { race ->
                beforeCandidate = null
                race()
            }
            if (holdCandidate) {
                holdCandidate = false
                paused.complete(Unit)
                resume.await()
            }
            failCandidate?.let { throw it }
            val written = memory.putCandidate(key, expected, candidate)
            if (written && loseCandidateReply) {
                loseCandidateReply = false
                error("the reply was lost")
            }
            return written
        }

        override suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean {
            beforeSlot?.let { race ->
                beforeSlot = null
                race()
            }
            val written = memory.putStage(key, expected, slot)
            if (written) {
                afterSlot?.let { race ->
                    afterSlot = null
                    race()
                }
            }
            return written
        }
    }

    private val store = Racing(memory)
    private val harness = ChangeHarness(clock, memory, store)
    private val changes = harness.changes

    private suspend fun published() {
        harness.revisions.publish(ACME, null, StoreFixtures.candidate(ACME), StoreFixtures.publication())
    }

    private suspend fun proposed(name: String): ChangeRequest =
        changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow(name))), name).also {
            it.state.assert().isEqualTo(ChangeState.CHECKED)
        }

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    private suspend fun holder(): ChangeId? = memory.stage(ACME)?.value?.holder

    private suspend fun candidate(): String? = memory.candidate(ACME)?.window

    @Test
    fun `a stage writing its candidate keeps the slot from another change - #1235`() = runBlocking<Unit> {
        published()
        val first = proposed("first")
        val second = proposed("second")
        store.holdCandidate = true
        val staging = async(start = CoroutineStart.UNDISPATCHED) { changes.stage(ALICE_OP, first.id) }
        store.paused.await()

        refused { changes.stage(ALICE_OP, second.id) }.assert().isEqualTo(409)
        changes.get(second.id).state.assert().isEqualTo(ChangeState.CHECKED)

        store.resume.complete(Unit)
        val staged = staging.await()
        staged.state.assert().isEqualTo(ChangeState.SHADOWING)
        holder().assert().isEqualTo(first.id)
        candidate().assert().isEqualTo(staged.shadow!!.window)
    }

    @Test
    fun `a change is staged by the time it holds the slot - #1235`() = runBlocking<Unit> {
        published()
        val first = proposed("first")
        val second = proposed("second")
        var refusal = 0
        store.afterSlot = { refusal = refused { changes.stage(ALICE_OP, second.id) } }

        changes.stage(ALICE_OP, first.id).state.assert().isEqualTo(ChangeState.SHADOWING)

        refusal.assert().isEqualTo(409)
        holder().assert().isEqualTo(first.id)
    }

    @Test
    fun `two stages of one change write one candidate - #1235`() = runBlocking<Unit> {
        published()
        val change = proposed("only")
        store.holdCandidate = true
        val staging = async(start = CoroutineStart.UNDISPATCHED) { changes.stage(ALICE_OP, change.id) }
        store.paused.await()
        clock.now = clock.now.plusMillis(1)

        refused { changes.stage(ALICE_OP, change.id) }.assert().isEqualTo(409)

        store.resume.complete(Unit)
        val staged = staging.await()
        candidate().assert().isEqualTo(staged.shadow!!.window)
        changes.get(change.id).shadow!!.window.assert().isEqualTo(staged.shadow.window)
    }

    /** Both returned changes are checked against the store: whichever won, it is the holder and owns the candidate. */
    @Test
    fun `of two stages at once, the one that returns is the holder and owns the candidate - #1235`() = runBlocking<Unit> {
        repeat(RACES) { race ->
            val clock = MutableClock()
            val memory = InMemoryControlStore(clock)
            val harness = ChangeHarness(clock, memory)
            harness.revisions.publish(ACME, null, StoreFixtures.candidate(ACME), StoreFixtures.publication())
            val ids = listOf("a", "b").map {
                harness.changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow(it))), it).id
            }
            val answers = ids.map { id ->
                async(Dispatchers.Default) {
                    try {
                        harness.changes.stage(ALICE_OP, id)
                    } catch (_: ChangeRejectedException) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
            answers.size.assert().describedAs("race $race").isEqualTo(1)
            memory.stage(ACME)?.value?.holder.assert().describedAs("race $race").isEqualTo(answers.single().id)
            memory.candidate(ACME)?.window.assert().describedAs("race $race")
                .isEqualTo(answers.single().shadow!!.window)
            (ids - answers.single().id).forEach {
                harness.changes.get(it).state.assert().describedAs("race $race").isEqualTo(ChangeState.CHECKED)
            }
        }
    }

    @Test
    fun `a stage that loses the slot is checked again, holding nothing - #1235`() = runBlocking<Unit> {
        published()
        val first = proposed("first")
        val second = proposed("second")
        var other: ChangeRequest? = null
        store.beforeSlot = { other = changes.stage(ALICE_OP, second.id) }

        refused { changes.stage(ALICE_OP, first.id) }.assert().isEqualTo(409)

        val lost = changes.get(first.id)
        lost.state.assert().isEqualTo(ChangeState.CHECKED)
        lost.shadow.assert().isNull()
        holder().assert().isEqualTo(second.id)
        candidate().assert().isEqualTo(other!!.shadow!!.window)
    }

    @Test
    fun `skipping the window of a change that loses the slot leaves it checked, not skipped - #1235`() = runBlocking<Unit> {
        published()
        val first = proposed("first")
        val second = proposed("second")
        changes.stage(ALICE_OP, second.id)

        refused { changes.skipShadow(ALICE_OP, first.id, "hurry") }.assert().isEqualTo(409)

        val lost = changes.get(first.id)
        lost.state.assert().isEqualTo(ChangeState.CHECKED)
        lost.skipShadow.assert().isNull()
    }

    @Test
    fun `a candidate that cannot be written gives the slot back - #1235`() = runBlocking<Unit> {
        published()
        val first = proposed("first")
        store.failCandidate = IllegalStateException("the store is down")

        refused { changes.stage(ALICE_OP, first.id) }.assert().isEqualTo(503)

        store.failCandidate = null
        changes.get(first.id).let {
            it.state.assert().isEqualTo(ChangeState.CHECKED)
            it.shadow.assert().isNull()
        }
        holder().assert().isNull()
        candidate().assert().isNull()
        changes.stage(ALICE_OP, proposed("second").id).state.assert().isEqualTo(ChangeState.SHADOWING)
    }

    @Test
    fun `a candidate written with its reply lost is withdrawn - #1235`() = runBlocking<Unit> {
        published()
        val first = proposed("first")
        store.loseCandidateReply = true

        refused { changes.stage(ALICE_OP, first.id) }.assert().isEqualTo(503)

        changes.get(first.id).state.assert().isEqualTo(ChangeState.CHECKED)
        holder().assert().isNull()
        candidate().assert().isNull()
    }

    @Test
    fun `a stage cancelled while writing its candidate gives the slot back and stays cancelled - #1235`() =
        runBlocking<Unit> {
            published()
            val first = proposed("first")
            store.holdCandidate = true
            val staging = async(start = CoroutineStart.UNDISPATCHED) { changes.stage(ALICE_OP, first.id) }
            store.paused.await()

            staging.cancelAndJoin()

            staging.isCancelled.assert().isTrue()
            changes.get(first.id).let {
                it.state.assert().isEqualTo(ChangeState.CHECKED)
                it.shadow.assert().isNull()
            }
            holder().assert().isNull()
            candidate().assert().isNull()
            changes.stage(ALICE_OP, proposed("second").id).state.assert().isEqualTo(ChangeState.SHADOWING)
        }

    @Test
    fun `a change abandoned while its candidate was written leaves none - #1235`() = runBlocking<Unit> {
        published()
        val first = proposed("first")
        store.holdCandidate = true
        val staging = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                changes.stage(ALICE_OP, first.id)
            } catch (e: ChangeRejectedException) {
                e
            }
        }
        store.paused.await()

        changes.abandon(ALICE_OP, first.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        store.resume.complete(Unit)

        (staging.await() as ChangeRejectedException).status.assert().isEqualTo(409)
        changes.get(first.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        candidate().assert().isNull()
    }

    /**
     * A change that may preempt takes the slot from a stage still writing its candidate, as it would from one that
     * finished. The stage answers 409, and its late write replaces nothing: the candidate is the preempting change's
     * (the window #1290 left open: the write landed, and the stage's own withdrawal then took the other's candidate
     * with it).
     */
    @Test
    fun `a stage preempted while writing its candidate answers 409 and leaves the preempting change's candidate - #1235`() =
        runBlocking<Unit> {
            harness.revisions.publish(
                ACME,
                null,
                StoreFixtures.candidate(ACME, listOf(allow("reads")), listOf(bind("readers", "reads"))),
                StoreFixtures.publication(),
            )
            val first = proposed("first")
            val narrowing = changes.propose(
                ALICE_OP,
                ACME,
                1,
                listOf(Operation.RemoveBinding(BindingId("readers"))),
                "ban"
            )
            store.holdCandidate = true
            val staging = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    changes.stage(ALICE_OP, first.id)
                } catch (e: ChangeRejectedException) {
                    e
                }
            }
            store.paused.await()
            val window = changes.get(first.id).shadow!!.window

            val preempting = changes.stage(ALICE_OP, narrowing.id)
            preempting.state.assert().isEqualTo(ChangeState.SHADOWING)
            store.resume.complete(Unit)

            (staging.await() as ChangeRejectedException).status.assert().isEqualTo(409)
            changes.get(first.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
            holder().assert().isEqualTo(narrowing.id)
            candidate().assert().isNotEqualTo(window)
            candidate().assert().isEqualTo(preempting.shadow!!.window)
        }

    /**
     * The same race with the stalled write landing before the preempting change has written its own: the preempting
     * change writes over it, and the candidate is its own whichever write came first.
     */
    @Test
    fun `a preempting change replaces the candidate a preempted stage wrote late - #1235`() = runBlocking<Unit> {
        harness.revisions.publish(
            ACME,
            null,
            StoreFixtures.candidate(ACME, listOf(allow("reads")), listOf(bind("readers", "reads"))),
            StoreFixtures.publication(),
        )
        val first = proposed("first")
        val narrowing = changes.propose(ALICE_OP, ACME, 1, listOf(Operation.RemoveBinding(BindingId("readers"))), "ban")
        store.holdCandidate = true
        val staging = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                changes.stage(ALICE_OP, first.id)
            } catch (e: ChangeRejectedException) {
                e
            }
        }
        store.paused.await()
        // The preempting change has read the candidate (none) and holds the slot; before it writes, the stalled write lands.
        store.beforeCandidate = {
            store.resume.complete(Unit)
            staging.await()
        }

        val preempting = changes.stage(ALICE_OP, narrowing.id)

        (staging.await() as ChangeRejectedException).status.assert().isEqualTo(409)
        holder().assert().isEqualTo(narrowing.id)
        candidate().assert().isEqualTo(preempting.shadow!!.window)
    }

    private companion object {
        const val RACES = 200
    }
}
