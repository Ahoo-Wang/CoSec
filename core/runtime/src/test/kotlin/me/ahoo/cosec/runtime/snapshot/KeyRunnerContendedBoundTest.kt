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
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Ask
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `SNP-14` under contention, over the [KeyRunner] (RFC 0013 B2b step 2b): the least recently used keys are held, each
 * by a thread stopped between taking an event and its transition, more of them than the cap's slack. An install over
 * the cap offers them their evictions, which wait for those threads, and evicts others in their place, so it answers
 * within the cap. Once the holders go on, the evictions they find queued never take the family below its bound.
 *
 * Above the cap it could answer only with every other key held at once, each in a drain that loads, reloads or evicts
 * it: the documents SNP-14 already counts as "being loaded or reloaded".
 */
class KeyRunnerContendedBoundTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        (0..CAP).forEach { put(app(it), document(1)) }
    }
    private val compiler = FakeCompiler()
    private val holders = mutableSetOf<Thread>()
    private val holding = CountDownLatch(HOLDERS)
    private val release = CountDownLatch(1)

    /** A holder's reload stops once taken, before its transition: the key's flag stays with that thread. */
    private val hooks = KeyDrain.Hooks({ event ->
        if (event is KeyEvent.Requested && event.ask is Ask.Reload && Thread.currentThread() in holders) {
            holding.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
    })
    private val housekeeping = SlotModelThreads().housekeeping
    private val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = BOUND)
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
        housekeeping,
        keyRunners(hooks = hooks),
    )

    private fun app(n: Int) = SnapshotKey.App("app-$n")

    private fun documentsHeld() = source.entries().count { it.key is SnapshotKey.App }

    private fun countsAreARecount() {
        source.heldCounts(SnapshotKind.APP).first.assert().isEqualTo(documentsHeld())
    }

    @Test
    fun `an install over the cap evicts around victims whose flag another thread holds, and they never go below the bound`() {
        source.load().join()
        // The cap, installed in order, housekeeping held: app-0 is the least recently used.
        (0 until CAP).forEach { source.load(app(it).appId).join() }
        documentsHeld().assert().isEqualTo(CAP)
        val reloads = (0 until HOLDERS).map { n ->
            thread(name = "holder-$n") { source.reload(app(n)) }.also { synchronized(holders) { holders += it } }
        }
        // Registered before each starts its reload: the set is read in the hook, on the holder's own thread.
        holding.await(10, TimeUnit.SECONDS).assert().isTrue()

        source.load(app(CAP).appId).join()
        // The held victims' evictions wait; the others went in their place, on this thread, before it answered.
        val held = (0 until HOLDERS).map(::app)
        source.entries().map { it.key }.filterIsInstance<SnapshotKey.App>().assert().containsAll(held)
        documentsHeld().assert().isLessThanOrEqualTo(CAP)
        countsAreARecount()

        release.countDown()
        reloads.forEach { it.join(10_000) }
        // Each holder's drain goes on to the eviction queued meanwhile, claimed only above the bound.
        documentsHeld().assert().isBetween(BOUND, CAP)
        countsAreARecount()
    }

    private companion object {
        const val BOUND = 4
        const val CAP = 2 * BOUND

        /** More than the cap's slack (CAP - BOUND): the pass cannot reach the bound without one of them. */
        const val HOLDERS = CAP - BOUND + 1
    }
}
