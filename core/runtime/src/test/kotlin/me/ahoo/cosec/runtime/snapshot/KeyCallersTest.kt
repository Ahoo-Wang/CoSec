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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [KeyCallers], the caller side of a key's drain on a stack that an idle key does not hold (RFC 0013 §5.4): an offer
 * at either of the drain's two edges is taken once, by the drainer at a free budget and by the hand-off at a spent one;
 * a remainder a spent budget leaves is taken before anything offered after it. `KeyDrainTest` has the two edges at a free
 * budget.
 */
class KeyCallersTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val compiler = FakeCompiler()

    /** Hand-offs, held until the test runs them; everything else (the compilations) runs in place. */
    private val handOffs = CopyOnWriteArrayList<Runnable>()
    private val pool = Executor { task ->
        // `DrainHandOffs` submits `::run`, which has no type of its own: told by its class's name.
        if (task.javaClass.name.contains("DrainHandOffs")) handOffs += task else task.run()
    }

    /** The recompile marks taken, in the order their drain took them. */
    private val taken = CopyOnWriteArrayList<CompletableFuture<*>>()

    private fun source(k: Int, hooks: KeyDrain.Hooks) = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, OPTIONS.maxApps, OPTIONS.maxAbsent)),
        OPTIONS,
        ManualClock(),
        SnapshotMetrics.NOOP,
        pool,
        null,
        { 0L },
        null,
        keyRunners(k, hooks) { it == APP_A },
    )

    private fun SnapshotSource<*, *>.slotA(): KeySlot<*> =
        families.first { it.spec.kind == SnapshotKind.APP }.slots.getValue("app-a")

    private fun runHandOffs() {
        while (handOffs.isNotEmpty()) handOffs.removeAt(0).run()
    }

    private fun recording(edge: KeyDrain.Hooks) = KeyDrain.Hooks(
        beforeNext = { if (it is KeyEvent.Recompile) taken += it.reply },
        afterEmpty = edge.afterEmpty,
        afterRelease = edge.afterRelease,
    )

    @Test
    fun `at a spent budget, an offer between the empty take and the release is handed off and taken once`() =
        lostAtSpentBudget { late -> KeyDrain.Hooks(afterEmpty = late) }

    @Test
    fun `at a spent budget, an offer between the release and the check is handed off and taken once`() =
        lostAtSpentBudget { late -> KeyDrain.Hooks(afterRelease = late) }

    /**
     * K = 0: the change is the one caller event this thread takes. One recompile mark offered at the edge [hooks] names
     * finds the budget spent: it is not taken before the drainer returns, and the hand-off takes it, once.
     */
    private fun lostAtSpentBudget(hooks: (late: () -> Unit) -> KeyDrain.Hooks) {
        val armed = AtomicBoolean()
        val late = CopyOnWriteArrayList<CompletableFuture<Boolean>>()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        source = source(0, recording(hooks { if (armed.getAndSet(false)) late += source.slotA().markRecompile() }))
        source.use {
            source.load("app-a").join()
            runHandOffs()
            armed.set(true)
            source.slotA().changed()
            late.assert().hasSize(1)
            taken.assert().isEmpty()
            handOffs.assert().isNotEmpty()
            runHandOffs()
            late.single().isDone.assert().isTrue()
            taken.assert().containsExactly(late.single())
        }
    }

    @Test
    fun `a remainder a spent budget leaves is taken before an event offered after it, each once`() {
        val offered = CopyOnWriteArrayList<CompletableFuture<*>>()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        var step = 0
        val hooks = KeyDrain.Hooks(beforeNext = { event ->
            if (event is KeyEvent.Recompile) {
                taken += event.reply
                // The first mark's step queues two more; the second's, one more: the budget of two leaves a remainder.
                when (++step) {
                    1 -> repeat(2) { offered += source.slotA().markRecompile() }
                    2 -> offered += source.slotA().markRecompile()
                }
            }
        })
        source = source(1, hooks)
        source.use {
            source.load("app-a").join()
            runHandOffs()
            taken.clear()
            // Its steps queue the others before it returns: `queued` holds them in the order they were offered.
            val first = source.slotA().markRecompile()
            val queued = offered.toList()
            queued.assert().hasSize(3)
            // K = 1: this thread takes two caller events, the first mark and the oldest one queued; it swapped out the
            // two its first step queued, so the second stays as the remainder, and the third came after the swap.
            taken.assert().containsExactly(first, queued[0])
            runHandOffs()
            taken.assert().containsExactly(first, queued[0], queued[1], queued[2])
            (queued + first).all { it.isDone }.assert().isTrue()
        }
    }

    private companion object {
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
    }
}
