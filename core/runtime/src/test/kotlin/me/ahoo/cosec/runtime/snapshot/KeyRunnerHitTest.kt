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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * RFC 0013 §5.4: a request queued while its key's load is in flight, and taken only after that load's end, is served
 * the install, counted as a HIT, not joined to the load as a COLD one. The load's end is taken from the load lane ahead
 * of it. The request gets the entry the load installed; only the outcome metric says when it was taken.
 */
class KeyRunnerHitTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val compiler = FakeCompiler()
    private val outcomes = CopyOnWriteArrayList<LoadOutcome>()
    private val metrics = object : SnapshotMetrics {
        override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
            if (kind == SnapshotKind.APP) outcomes += outcome
        }
    }
    private val handOffs = CopyOnWriteArrayList<Runnable>()

    /**
     * Compilations run in place; a hand-off is held until the test runs it. Told by its class name: `DrainHandOffs`
     * submits `::run`, which has no type of its own, and a seam in main for this one test would be a tests-only member.
     */
    private val pool = Executor { task ->
        if (task.javaClass.name.contains("DrainHandOffs")) handOffs += task else task.run()
    }

    private fun source(slots: SlotFactory): SnapshotSource<Compiled, Compiled> = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, OPTIONS.maxApps, OPTIONS.maxAbsent)),
        OPTIONS,
        ManualClock(),
        metrics,
        pool,
        null,
        { 0L },
        null,
        slots,
    )

    /** A's cold load in flight; a second request for A offered with a spent budget; then the load's read answers. */
    private fun twoRequests(slots: SlotFactory): List<LoadOutcome> {
        source(slots).use { source ->
            source.load().join()
            store.hold = true
            val first = source.load(APP_A.appId)
            val second = DrainBudget.within(0) { source.load(APP_A.appId) }
            store.pending.single { it.key == APP_A }.release()
            while (handOffs.isNotEmpty()) handOffs.removeAt(0).run()
            (first.join().app is DocumentState.Present).assert().isTrue()
            (second.join().app is DocumentState.Present).assert().isTrue()
        }
        return outcomes.toList()
    }

    @Test
    fun `the runner serves a request taken after its key's load ends from the install, a hit`() {
        twoRequests(keyRunners(k = 0)).assert().containsExactly(LoadOutcome.COLD, LoadOutcome.HIT)
    }

    private companion object {
        val APP_A = SnapshotKey.App("app-a")
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
    }
}
