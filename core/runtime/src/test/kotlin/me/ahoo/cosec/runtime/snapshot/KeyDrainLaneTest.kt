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
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The load lane (RFC 0013 B2b, finding 2): what the source's own work produces is taken by whoever holds the key's
 * flag, never counted against a caller's budget and never handed off; only caller events are. The compile pool serves
 * the hand-offs between compilations. Most cases run at K = 0, where a caller's budget is spent by its own event, on a
 * compile pool that holds what it is given until the test runs it.
 */
class KeyDrainLaneTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val compiler = FakeCompiler()
    private val held = CopyOnWriteArrayList<Runnable>()

    @Volatile
    private var holding = false
    private val pool = Executor { task -> if (holding) held += task else task.run() }

    private fun source(
        hooks: KeyDrain.Hooks,
        k: Int = 0,
        compileExecutor: Executor = pool,
        compiler: SnapshotCompiler<Compiled> = this.compiler,
    ) = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, OPTIONS.maxApps, OPTIONS.maxAbsent)),
        OPTIONS,
        ManualClock(),
        SnapshotMetrics.NOOP,
        compileExecutor,
        null,
        { 0L },
        null,
        keyRunners(k, hooks) { it == APP_A },
    )

    private fun SnapshotSource<*, *>.slotA(): KeyRunner<*> =
        families.first { it.spec.kind == SnapshotKind.APP }.slots.getValue("app-a")

    @Test
    fun `a lane event offered between the empty queue and the release is taken before the drainer returns`() =
        laneLostAt { late -> KeyDrain.Hooks(afterEmpty = late) }

    @Test
    fun `a lane event offered between the release and the check is taken before the drainer returns`() =
        laneLostAt { late -> KeyDrain.Hooks(afterRelease = late) }

    /**
     * At K = 0 this thread's one caller event is the change; a retry offered at the edge [hooks] names comes after that
     * budget is spent. It is taken before the change returns, though the pool, which would run a hand-off, runs nothing.
     */
    private fun laneLostAt(hooks: (late: () -> Unit) -> KeyDrain.Hooks) {
        val retries = AtomicInteger()
        val armed = AtomicBoolean()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val edge = hooks { if (armed.getAndSet(false)) (source.slotA() as KeyRunner<*>).offer(KeyEvent.RetryDue()) }
        source = source(
            KeyDrain.Hooks({ if (it is KeyEvent.RetryDue) retries.incrementAndGet() }, edge.afterEmpty, edge.afterRelease)
        )
        source.use {
            source.load("app-a").join()
            holding = true
            armed.set(true)
            source.slotA().changed()
            armed.get().assert().describedAs("the edge was reached").isFalse()
            retries.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `a failed compilation offered while a caller with a spent budget holds the flag is installed before it returns`() {
        val armed = AtomicBoolean()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        // While this thread holds A's flag with its budget spent, the pool compiles on its own thread and offers the
        // result: the flag is taken, so the offer only enqueues it.
        val hooks = KeyDrain.Hooks(afterEmpty = {
            if (armed.getAndSet(false)) thread { held.removeAt(0).run() }.join()
        })
        source = source(hooks)
        source.use {
            source.load("app-a").join()
            store.put(APP_A, document(2, "THROW"))
            holding = true
            armed.set(true)
            // K = 0: the change is this thread's one caller event; the read's answer and the compilation are the lane's.
            source.slotA().changed()
            armed.get().assert().describedAs("the compilation ran while the flag was held").isFalse()
            val installed = source.slotA().installed!!
            installed.revision.assert().isEqualTo(2L)
            installed.state.assert().isInstanceOf(DocumentState.Failed::class.java)
        }
    }

    @Test
    fun `the compile pool serves a hand-off when the compilation it runs ends, before the ones queued behind it`() {
        val order = CopyOnWriteArrayList<String>()
        val gate = CountDownLatch(1)
        val gated = object : SnapshotCompiler<Compiled> {
            override fun compile(key: SnapshotKey, document: SnapshotDocument): Compiled {
                if (key is SnapshotKey.App) order += "compile ${key.appId}"
                if (key == APP_B) gate.await(WAIT_SECONDS, TimeUnit.SECONDS)
                return compiler.compile(key, document)
            }
        }
        listOf(APP_B, APP_C, APP_D).forEach { store.put(it, document(1)) }
        val single = Executors.newSingleThreadExecutor()
        try {
            source(
                KeyDrain.Hooks.NONE,
                k = KeyRunner.K,
                compileExecutor = single,
                compiler = gated
            ).use { source ->
                source.load("app-a").join()
                order.clear()
                // One compilation running on the pool's one thread, two queued behind it.
                val loads = listOf("app-b", "app-c", "app-d").map { source.load(it) }
                awaitUntil { "compile app-b" in order }
                // A caller's event left by a spent budget: handed off, its task queued behind the two compilations.
                val handedOff = DrainBudget.within(0) { source.slotA().markRecompile() }
                handedOff.isDone.assert().isFalse()
                handedOff.whenComplete { _, _ -> order += "hand-off" }
                gate.countDown()
                CompletableFuture.allOf(handedOff, *loads.toTypedArray()).get(WAIT_SECONDS, TimeUnit.SECONDS)
                order.take(4).assert().containsExactly("compile app-b", "hand-off", "compile app-c", "compile app-d")
            }
        } finally {
            gate.countDown()
            single.shutdownNow()
        }
    }

    @Test
    fun `a lane event offered a second time fails`() {
        source(KeyDrain.Hooks.NONE, compileExecutor = directExecutor).use { source ->
            source.load("app-a").join()
            val runner = source.slotA() as KeyRunner<*>
            val retry = KeyEvent.RetryDue()
            runner.offer(retry)
            assertThrows<AssertionError> { runner.offer(retry) }
        }
    }

    /**
     * K = 4, a burst of reloads queued behind a caller's own: the loads they start read and compile at once, on its
     * thread. It takes K + 1 of the reloads and, uncounted, the lane events of their loads, and hands the rest off. The
     * bound is (K + 1) × (1 + [KeyDrain.LANE_EVENTS_PER_LOAD]) + [KeyDrain.LANE_EVENTS_PER_LOAD].
     */
    @Test
    fun `a caller's burst at K = 4 takes K + 1 caller events and their loads' lane events, and hands the rest off`() {
        val caller = Thread.currentThread()
        val counting = AtomicBoolean()
        val taken = CopyOnWriteArrayList<String>()
        val kinds = CopyOnWriteArrayList<String>()
        val queued = CopyOnWriteArrayList<CompletableFuture<*>>()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val hooks = KeyDrain.Hooks({
            if (counting.get() && Thread.currentThread() === caller) {
                taken += if (it is KeyEvent.LaneEvent<*>) "lane" else "caller"
                kinds += it::class.simpleName!!
                if (taken.size == 1) repeat(BURST) { queued += source.slotA().reload(force = true) }
            }
        })
        // Compilations run in place; a hand-off is held, so what this thread did not take stays visible.
        val handOffsHeld = Executor { task ->
            if (task.javaClass.name.contains("DrainHandOffs")) held += task else task.run()
        }
        source = source(hooks, k = K, compileExecutor = handOffsHeld)
        source.use {
            source.load("app-a").join()
            counting.set(true)
            source.slotA().reload(force = true)
            counting.set(false)
            taken.count { it == "caller" }.assert().isEqualTo(K + 1)
            // The lane is taken whole before each caller event: the second reload finds the first load compiling, and
            // shares the load after it with the third. Five reloads, four loads, each a read's answer and a compilation.
            kinds.assert().containsExactly(
                "Requested", "ReadDone", "Requested", "Compiled", "Requested", "ReadDone", "Requested", "Compiled",
                "Requested", "ReadDone", "Compiled", "ReadDone", "Compiled",
            )
            taken.size.assert().isLessThanOrEqualTo(
                (K + 1) * (1 + KeyDrain.LANE_EVENTS_PER_LOAD) + KeyDrain.LANE_EVENTS_PER_LOAD
            )
            held.assert().isNotEmpty()
            queued.count { !it.isDone }.assert().isEqualTo(BURST - K)
        }
    }

    /**
     * Seed 160 of the model at K = 0: an absent key loading at `recompile()`, whose read answers before the handed-off
     * mark is taken. The load's end is judged by epoch, so another load follows it, and the recompile completes only
     * after a read that started after the call (`SNP-13`).
     */
    @Test
    fun `a load of an absent key that ends before its recompile mark is taken is followed by a read after the call`() {
        store.remove(APP_A)
        source(KeyDrain.Hooks.NONE).use { source ->
            source.load("app-a").join()
            store.hold = true
            holding = true
            source.slotA().reload(force = false)
            val before = store.pending.single { it.key == APP_A }
            val recompiling = DrainBudget.within(0) { source.recompile() }
            held.assert().describedAs("the mark is handed off").isNotEmpty()
            // The read in flight at the call answers first: absent, taken from the lane at once.
            before.release()
            val after = store.pending.filter { it.key == APP_A && it.sequence > before.sequence }
            after.assert().describedAs("a read started after the call").hasSize(1)
            held.removeAt(0).run()
            recompiling.isDone.assert().describedAs("done with no read after the call ended").isFalse()
            repeat(ROUNDS) {
                if (recompiling.isDone) return@repeat
                store.pending.firstOrNull { it.sequence > before.sequence && !it.future.isDone }?.release()
                while (held.isNotEmpty()) held.removeAt(0).run()
            }
            recompiling.isDone.assert().isTrue()
        }
    }

    /** In arrival order, a recompile during a load is followed by exactly one load: the mark sets nothing of its own. */
    @Test
    fun `a recompile during a load is followed by one load, not two`() {
        source(KeyDrain.Hooks.NONE, k = KeyRunner.K, compileExecutor = directExecutor).use { source ->
            source.load("app-a").join()
            store.hold = true
            source.slotA().reload(force = false)
            val before = store.pending.single { it.key == APP_A }
            val recompiling = source.recompile()
            before.release()
            repeat(ROUNDS) {
                if (recompiling.isDone) return@repeat
                store.pending.firstOrNull { it.sequence > before.sequence && !it.future.isDone }?.release()
            }
            recompiling.isDone.assert().isTrue()
            store.pending.count { it.key == APP_A && it.sequence > before.sequence }.assert().isEqualTo(1)
        }
    }

    /**
     * The derivation of [KeyDrain.LANE_EVENTS_PER_LOAD], by kind: a load's read answers once, times out once, its
     * compilation ends once, it is retried once, and each of those steps and the one that started it raises at most one
     * `EffectFailed`. A new kind of lane event does not compile here until it is counted.
     */
    @Test
    fun `a load offers at most nine lane events, counted by kind`() {
        fun group(event: KeyEvent.LaneEvent<*>): String = when (event) {
            is KeyEvent.ReadDone, is KeyEvent.ReadFailed -> "read answered"
            is KeyEvent.ReadTimedOut -> "read timed out"
            is KeyEvent.Compiled, is KeyEvent.CompileCrashed, is KeyEvent.CompileRejected -> "compilation ended"
            is KeyEvent.RetryDue -> "retried"
            is KeyEvent.EffectFailed -> "effect failed"
        }
        val failure = IllegalStateException("thrown by the test")
        val kinds = listOf(
            KeyEvent.ReadDone(1, SnapshotRead.Absent),
            KeyEvent.ReadFailed(1, failure),
            KeyEvent.ReadTimedOut(1),
            KeyEvent.Compiled(1, DocumentState.Present(Compiled(APP_A, 1, emptyList(), emptySet()))),
            KeyEvent.CompileCrashed(1, SnapshotUnavailableException(APP_A, "thrown by the test", failure)),
            KeyEvent.CompileRejected(1, RejectedExecutionException("thrown by the test")),
            KeyEvent.RetryDue(),
            KeyEvent.EffectFailed(1, failure),
        ).groupBy(::group).keys
        val once = kinds - "effect failed"
        // The step that started the load, and one for each event of it taken once.
        val steps = 1 + once.size
        (once.size + steps).assert().isEqualTo(KeyDrain.LANE_EVENTS_PER_LOAD)
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met in time." }
            Thread.sleep(1)
        }
    }

    private companion object {
        val APP_A = SnapshotKey.App("app-a")
        val APP_B = SnapshotKey.App("app-b")
        val APP_C = SnapshotKey.App("app-c")
        val APP_D = SnapshotKey.App("app-d")
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
        const val K = 4
        const val BURST = 20
        const val ROUNDS = 10
        const val WAIT_SECONDS = 10L
    }
}
