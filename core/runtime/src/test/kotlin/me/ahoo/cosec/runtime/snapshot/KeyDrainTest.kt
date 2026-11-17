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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [KeyDrain] (RFC 0013 B2b §3 and §5), through runners made by the source's factory: an offer that loses the flag at
 * either of the drain's two edges is taken before the drainer returns; offers from many threads are each taken once;
 * a transition that throws fails what its event carries and the load it belongs to, and the key takes the next event;
 * close answers every future handed out, a hand-off pending in a pool that never runs included.
 */
class KeyDrainTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val compiler = FakeCompiler()

    private fun source(
        hooks: KeyDrain.Hooks,
        k: Int = KeyRunner.PROVISIONAL_K,
        compileExecutor: Executor? = directExecutor,
        options: SnapshotOptions = OPTIONS,
        everyKey: Boolean = false,
    ) = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsent)),
        options,
        ManualClock(),
        SnapshotMetrics.NOOP,
        compileExecutor,
        null,
        { 0L },
        null,
        object : SlotFactory {
            override fun <T : Any> create(
                key: SnapshotKey,
                compiler: SnapshotCompiler<T>,
                context: SlotContext
            ): KeySlot<T> =
                KeyRunner(key, compiler, context, k, if (everyKey || key == APP_A) hooks else KeyDrain.Hooks.NONE)
        },
    )

    private fun SnapshotSource<*, *>.slotA(): KeySlot<*> = families.first {
        it.spec.kind == SnapshotKind.APP
    }.slots.getValue("app-a")

    @Test
    fun `an offer that loses the flag between the empty queue and the release is taken before the drainer returns`() =
        lostAt { late -> KeyDrain.Hooks(afterEmpty = late) }

    @Test
    fun `an offer that loses the flag between the release and the check is taken before the drainer returns`() =
        lostAt { late -> KeyDrain.Hooks(afterRelease = late) }

    /** One offer made at the edge [hooks] names, once A is installed, while this thread drains A: taken exactly once. */
    private fun lostAt(hooks: (late: () -> Unit) -> KeyDrain.Hooks) {
        val taken = AtomicInteger()
        val armed = AtomicBoolean()
        val late = CopyOnWriteArrayList<CompletableFuture<Boolean>>()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val edge = hooks { if (armed.getAndSet(false)) late += source.slotA().markRecompile() }
        val counting = KeyDrain.Hooks({
            if (it is KeyEvent.Recompile) taken.incrementAndGet()
        }, edge.afterEmpty, edge.afterRelease)
        source = source(counting)
        source.use {
            source.load("app-a").join()
            armed.set(true)
            source.slotA().changed()
            late.assert().hasSize(1)
            late.single().isDone.assert().isTrue()
            taken.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `offers from eight threads are each taken once, and no future is left open`() {
        val taken = AtomicInteger()
        source(KeyDrain.Hooks({ if (it is KeyEvent.Recompile) taken.incrementAndGet() })).use { source ->
            source.load("app-a").join()
            val slot = source.slotA()
            val start = CyclicBarrier(THREADS)
            val futures = CopyOnWriteArrayList<CompletableFuture<Boolean>>()
            (0 until THREADS).map {
                thread {
                    start.await()
                    repeat(EVENTS) { futures += slot.markRecompile() }
                }
            }.forEach { it.join() }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
            futures.assert().hasSize(THREADS * EVENTS)
            taken.get().assert().isEqualTo(THREADS * EVENTS)
        }
    }

    @Test
    fun `a transition that throws answers what its event carries as a failure, and the key takes the next event`() {
        // What each caller sees: a request fails closed (its document unavailable); an entry is a failed one; a recompile
        // mark says "load it again"; an eviction says "not removed".
        val carriers: List<Pair<String, (SnapshotSource<Compiled, Compiled>) -> Any?>> = listOf(
            "request" to { it.load("app-a").join().app },
            "load" to { ((it.slotA().load(mockk(relaxed = true)).join() as Reloaded.Loaded).entry.state) },
            "reload" to { it.slotA().reload(force = false).join().state },
            "reload if held" to { (it.slotA().reloadIfHeld(force = false).join() as Reloaded.Loaded).entry.state },
            "recompile" to { it.slotA().markRecompile().join() },
            "evict" to { it.slotA().evict(onlyIfEmpty = false, claimedAbsent = null).join() },
        )
        val expected = mapOf("recompile" to true, "evict" to Eviction.REFUSED)
        for ((name, call) in carriers) {
            val armed = AtomicBoolean()
            val hooks = KeyDrain.Hooks({
                val carrying = it is KeyEvent.Requested || it is KeyEvent.Recompile || it is KeyEvent.Evict
                if (carrying && armed.getAndSet(false)) error("thrown by the test")
            })
            source(hooks).use { source ->
                // A request is cold, so its event is offered; every other carrier finds A installed.
                if (name == "request") source.load().join() else source.load("app-a").join()
                armed.set(true)
                val answered = call(source)
                if (name in expected) {
                    answered.assert().describedAs(name).isEqualTo(expected[name])
                } else {
                    (answered as DocumentState<*>).assert().describedAs(name).isInstanceOf(DocumentState.Failed::class.java)
                }
                (source.slotA().reloadIfHeld(force = false).join() as Reloaded.Loaded).entry.revision.assert().describedAs(name).isEqualTo(1L)
            }
        }
    }

    @Test
    fun `a transition that throws on the read of a load with waiters ends that load, and its waiters are answered`() {
        val armed = AtomicBoolean()
        source(
            KeyDrain.Hooks({
                if (it is KeyEvent.ReadDone && armed.getAndSet(false)) error("thrown by the test")
            })
        ).use { source ->
            source.load().join()
            store.hold = true
            armed.set(true)
            val waiting = source.load("app-a")
            store.pending.single { it.key == APP_A }.release()
            (waiting.join().app is DocumentState.Failed).assert().isTrue()
            store.hold = false
            (source.slotA().reloadIfHeld(force = false).join() as Reloaded.Loaded).entry.revision.assert().isEqualTo(1L)
        }
    }

    @Test
    fun `close answers every future handed out, with more than K events queued and a hand-off in a pool that never runs`() {
        val held = CopyOnWriteArrayList<Runnable>()
        val holding = AtomicBoolean()
        val pool = Executor { task -> if (holding.get()) held += task else task.run() }
        val more = CopyOnWriteArrayList<CompletableFuture<*>>()
        val armed = AtomicBoolean()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val hooks = KeyDrain.Hooks({
            if (it is KeyEvent.Recompile && armed.getAndSet(false)) {
                repeat(
                    EXTRA
                ) { more += source.slotA().markRecompile() }
            }
        })
        source = source(hooks, k = 0, compileExecutor = pool)
        source.load("app-a").join()
        holding.set(true)
        armed.set(true)
        more += source.slotA().markRecompile()
        held.isNotEmpty().assert().isTrue()
        val revalidating = source.revalidate()
        val recompiling = source.recompile()
        source.close()
        (more + revalidating + recompiling).forEach { it.isDone.assert().isTrue() }
    }

    @Test
    fun `the scheduler drains as a caller does, within its budget, and hands the rest off`() {
        val held = CopyOnWriteArrayList<Runnable>()
        val holding = AtomicBoolean()
        val pool = Executor { task -> if (holding.get()) held += task else task.run() }
        val takenOn = CopyOnWriteArrayList<String>()
        val more = CopyOnWriteArrayList<CompletableFuture<Boolean>>()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val hooks = KeyDrain.Hooks({
            if (it is KeyEvent.Recompile) takenOn += Thread.currentThread().name
            // On the timer's thread, while it holds A's flag: these are queued behind its own event.
            if (it is KeyEvent.ReadTimedOut) repeat(EXTRA) { more += source.slotA().markRecompile() }
        })
        source = source(hooks, k = 0, compileExecutor = pool, options = SnapshotOptions(revalidateInterval = QUIET_PASS, storeReadTimeout = java.time.Duration.ofMillis(50)))
        source.use {
            source.load().join()
            holding.set(true)
            store.hold = true
            // A's read hangs; the timer's ReadTimedOut is the scheduler's offer.
            source.load("app-a").join()
            more.assert().hasSize(EXTRA)
            takenOn.none { it.startsWith("cosec-snapshot-scheduler") }.assert().isTrue()
            held.assert().hasSize(1)
            held.single().run()
            more.all { it.isDone }.assert().isTrue()
        }
    }

    @Test
    fun `a recompile of a thousand keys from a thread not ours rejects no compilation, with a compile queue of 64`() {
        val rejected = AtomicInteger()
        val keys = (0 until MANY).map { SnapshotKey.App("app-$it") }
        keys.forEach { store.put(it, document(1)) }
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = MANY + 1, compileQueueCapacity = 64)
        source(KeyDrain.Hooks({ if (it is KeyEvent.CompileRejected) rejected.incrementAndGet() }), compileExecutor = null, options = options, everyKey = true).use { source ->
            // A cold burst of a thousand overruns a queue of 64 with Slot too; what is measured is the recompile.
            keys.chunked(CHUNK).forEach { source.loadAll(it).join() }
            rejected.set(0)
            source.recompile().join()
            rejected.get().assert().isEqualTo(0)
        }
    }

    @Test
    fun `a transition that throws on a pass's load ends the pass's read`() {
        val read = mockk<PassRead>(relaxed = true)
        val pass = mockk<Pass> { every { startRead() } returns read }
        val armed = AtomicBoolean()
        source(KeyDrain.Hooks({ if (it is KeyEvent.ReadDone && armed.getAndSet(false)) error("thrown by the test") })).use { source ->
            source.load("app-a").join()
            store.hold = true
            armed.set(true)
            val loading = source.slotA().load(pass)
            store.pending.single { it.key == APP_A }.release()
            loading.join()
            verify(exactly = 1) { read.end() }
        }
    }

    private companion object {
        const val MANY = 1_000
        const val CHUNK = 32
        val APP_A = SnapshotKey.App("app-a")
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
        const val THREADS = 8
        const val EVENTS = 10_000
        const val EXTRA = 5
    }
}
