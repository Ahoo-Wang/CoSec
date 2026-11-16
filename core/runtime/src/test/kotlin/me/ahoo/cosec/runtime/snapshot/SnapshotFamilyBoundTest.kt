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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * `SNP-14`: a family holds at most its bound plus the smaller of the bound and `MAX_SLACK`; an install that takes it
 * over evicts on the installing thread before it answers, and evictions never take a kind below its bound. The source
 * compiles on the test's thread (`directExecutor`), and its housekeeping is a queue the test runs, or holds.
 */
class SnapshotFamilyBoundTest {
    private val threads = SlotModelThreads()
    private val store = InMemorySnapshotDocumentStore()
    private val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = BOUND)
    private val compiler = FakeCompiler()
    private val source = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsent)),
        options,
        ManualClock(),
        SnapshotMetrics.NOOP,
        directExecutor,
        null,
        { 0L },
        threads.housekeeping,
    )

    init {
        store.put(SnapshotKey.Platform, document(1))
        (0 until APPS).forEach { store.put(SnapshotKey.App("app-$it"), document(1)) }
        source.load().join()
    }

    private fun documentsHeld() = source.entries().count { it.key is SnapshotKey.App && it.origin != EntryOrigin.ABSENT }

    private fun absentHeld() = source.entries().count { it.key is SnapshotKey.App && it.origin == EntryOrigin.ABSENT }

    private fun countsAreARecount() {
        source.heldCounts(SnapshotKind.APP).assert().describedAs(state()).isEqualTo(documentsHeld() to absentHeld())
    }

    /** What a failure must show: the counts, a recount of what is served, the loads in flight and the queued tasks. */
    private fun state(): String =
        "counts ${source.heldCounts(SnapshotKind.APP)}, recount ${documentsHeld() to absentHeld()}, " +
            "loads in flight ${source.loadsInFlight(SnapshotKind.APP)}, queued ${threads.housekeeping.tasks.size}"

    @Test
    fun `a burst with housekeeping held never holds more than the cap, and the installer evicted it in place`() {
        (0 until APPS).forEach { app ->
            source.load("app-$app").join()
            documentsHeld().assert().describedAs("after app-$app").isLessThanOrEqualTo(CAP)
            countsAreARecount()
        }
        // Nothing ran on housekeeping: every eviction happened on the thread that installed.
        threads.housekeeping.tasks.assert().isNotEmpty()
    }

    @Test
    fun `installs within the cap leave eviction to housekeeping, which takes the family back to its bound`() {
        (0 until CAP).forEach { source.load("app-$it").join() }
        documentsHeld().assert().isEqualTo(CAP)
        while (threads.housekeeping.tasks.isNotEmpty()) threads.runHousekeeping(0)
        documentsHeld().assert().isEqualTo(BOUND)
        countsAreARecount()
    }

    @Test
    fun `passes at once take a kind exactly to its bound, never below it and never stopping above it`() {
        // A pass that stopped on a claim another was about to give back left one above the bound in about 1 round in
        // 10,000 before claims moved under the victim's lock: so many rounds, two to four passes at a time.
        repeat(RACES) { round ->
            (0 until CAP).forEach { source.load("app-$it").join() }
            val passes = threads.housekeeping.tasks.toList().also { threads.housekeeping.tasks.clear() }
            val count = 2 + round % 3
            val barrier = CyclicBarrier(count)
            val racers = List(count) { passes[it % passes.size] }.map { pass ->
                thread {
                    barrier.await()
                    pass.run()
                }
            }
            racers.forEach { it.join() }
            // Settled: compilation runs on this thread, the store answers at once, and both passes have ended.
            documentsHeld().assert().describedAs(state()).isEqualTo(BOUND)
            countsAreARecount()
        }
    }

    @Test
    fun `a victim whose kind changed between the choice and the removal is not removed, and the claim goes back`() {
        (0 until CAP).forEach { source.load("app-$it").join() }
        // Chosen as an absent entry, it holds a document by the time it is removed.
        source.evictClaiming(SnapshotKey.App("app-0"), absent = true).assert().isEqualTo(false)
        source.evictClaiming(SnapshotKey.App("app-0"), absent = false).assert().isEqualTo(true)
        documentsHeld().assert().isEqualTo(CAP - 1)
        countsAreARecount()
        // An absent entry is not removed on a document's claim either.
        source.load("absent").join()
        source.evictClaiming(SnapshotKey.App("absent"), absent = false).assert().isEqualTo(false)
        source.holds(SnapshotKey.App("absent")).assert().isTrue()
        countsAreARecount()
    }

    private companion object {
        const val BOUND = 4
        const val CAP = 2 * BOUND
        const val APPS = 40
        const val RACES = 20_000
    }
}
