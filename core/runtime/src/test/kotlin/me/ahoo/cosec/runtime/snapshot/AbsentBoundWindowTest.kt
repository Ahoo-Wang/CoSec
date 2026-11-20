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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The window (RFC 0013 B2b, `SNP-7`, `SNP-14`): an eviction pass skips a member whose load is in flight, and refuses one
 * that starts a load before its eviction is taken. When that load ends without an install (the store answers absent
 * again), the member asks for the bound itself, or the family would stay above its absent bound until some later
 * install.
 * Deterministic: housekeeping runs only when a test says, and a held read answers only when released.
 */
class AbsentBoundWindowTest {
    private val threads = SourceModelThreads()
    private val store = ControllableStore().apply { put(SnapshotKey.Platform, document(1)) }
    private val compiler = FakeCompiler()
    private val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxAbsentApps = 1)
    private val a = SnapshotKey.App("absent-a")
    private val b = SnapshotKey.App("absent-b")

    /** Ordered, so the least recently used member is a, loaded first: the pass's choice is not a tie. */
    private val ticker = AtomicLong()

    private fun source(hooks: KeyDrain.Hooks = KeyDrain.Hooks.NONE) = SnapshotSource(
        store, SnapshotKey.Platform, compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsentApps)),
        options, ManualClock(), SnapshotMetrics.NOOP, directExecutor, null, ticker::incrementAndGet, threads.housekeeping,
        keyRunners(hooks = hooks),
    )

    private fun SnapshotSource<*, *>.absentHeld(): Int =
        families.first { it.spec.kind == SnapshotKind.APP }.count(absent = true).get()

    private fun release(key: SnapshotKey) {
        store.pending.filter { it.key == key && !it.future.isDone }.forEach { it.release() }
    }

    private fun runHousekeeping() {
        while (threads.housekeeping.tasks.isNotEmpty()) threads.runHousekeeping(0)
    }

    @Test
    fun `members loading when the last pass scans are evicted once their loads end absent again`() {
        source().use { source ->
            source.load().join()
            source.load(a.appId).join()
            runHousekeeping()
            store.hold = true
            // a is loading: a reload whose read is held.
            val reloading = source.reload(a)
            // b installs as absent; its confirming read is held, so it is loading too.
            val loadingB = source.load(b.appId)
            release(b)
            loadingB.join()
            source.absentHeld().assert().isEqualTo(2)
            // The pass that b's install queued: neither member is idle, so it evicts nothing.
            runHousekeeping()
            source.absentHeld().assert().isEqualTo(2)
            // Both loads end absent again: nothing installs, and each end asks for the bound.
            store.hold = false
            release(a)
            release(b)
            reloading.join()
            runHousekeeping()
            source.absentHeld().assert().describedAs("absent entries held").isLessThanOrEqualTo(1)
        }
    }

    @Test
    fun `a victim the pass chose that starts a load before its eviction is taken is evicted once that load ends`() {
        val passing = AtomicBoolean()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        // While a's drain takes the reload, the pass runs: it chose a, idle then, and its eviction queues behind the
        // reload, which starts a load, so the eviction is refused when it is taken.
        val hooks = KeyDrain.Hooks({
            if (it is KeyEvent.Requested && it.ask is KeyEvent.Ask.Reload && passing.getAndSet(false)) runHousekeeping()
        })
        source = source(hooks)
        source.use {
            source.load().join()
            source.load(a.appId).join()
            source.load(b.appId).join()
            source.absentHeld().assert().isEqualTo(2)
            store.hold = true
            passing.set(true)
            val reloading = source.reload(a)
            passing.get().assert().describedAs("the pass ran inside a's drain").isFalse()
            runHousekeeping()
            source.absentHeld().assert().describedAs("the refused victim is not replaced").isEqualTo(2)
            // a's load ends absent again: nothing installs, and its end asks for the bound.
            store.hold = false
            release(a)
            reloading.join()
            runHousekeeping()
            source.absentHeld().assert().describedAs("absent entries held").isLessThanOrEqualTo(1)
        }
    }

    /**
     * The one refusal no load in flight answers: an eviction whose transition throws gives its claim back, and the victim
     * stays, idle. Nothing asks at once, since a transition that throws every time would loop; the periodic revalidation
     * reloads every installed member, and each of those loads' ends asks for the bound.
     */
    @Test
    fun `an eviction whose transition throws is taken back to the bound by the next revalidation`() {
        val throwing = AtomicBoolean()
        val thrown = AtomicInteger()
        val hooks = KeyDrain.Hooks({
            if (it is KeyEvent.Evict && throwing.get()) error("thrown by the test, ${thrown.incrementAndGet()}")
        })
        source(hooks).use { source ->
            source.load().join()
            source.load(a.appId).join()
            source.load(b.appId).join()
            // Every pass b's loads asked for refuses: each eviction's transition throws, and its claim is given back.
            throwing.set(true)
            runHousekeeping()
            throwing.set(false)
            thrown.get().assert().describedAs("evictions whose transition threw").isGreaterThan(0)
            source.absentHeld().assert().describedAs("the claims given back").isEqualTo(2)
            // Both members load again, and the store answers absent again: nothing installs, each end asks.
            source.revalidate().join()
            runHousekeeping()
            source.absentHeld().assert().describedAs("absent entries held").isLessThanOrEqualTo(1)
        }
    }
}
