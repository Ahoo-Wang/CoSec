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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * What the lag pass publishes for the gauges of `cosec-micrometer` ([SnapshotSummary]): every entry held counted once,
 * in the state its origin maps to, so the states sum to [SnapshotSource.entries]; the largest lag and the documents past
 * the thresholds; and the evictions the families make, by kind and by what the key held, on a slot and on a runner.
 */
class SnapshotSummaryTest {
    private val clock = ManualClock()
    private val ticker = AtomicLong()
    private val options = SnapshotOptions(revalidateInterval = QUIET_PASS)

    private class Evictions : SnapshotMetrics {
        val evicted: MutableList<Pair<SnapshotKind, Boolean>> = CopyOnWriteArrayList()

        override fun onEvicted(kind: SnapshotKind, absent: Boolean) {
            evicted += kind to absent
        }
    }

    private fun source(
        store: ControllableStore,
        metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
        slots: SlotFactory = SlotFactory.SLOT,
    ): SnapshotSource<Compiled, Compiled> {
        val compiler = FakeCompiler()
        return SnapshotSource(
            store, SnapshotKey.Platform, compiler, DocumentSnapshots.families(compiler, options), options, clock,
            metrics, directExecutor, null, { ticker.incrementAndGet() }, null, slots,
        )
    }

    private fun SnapshotSummary.total(): Int = SnapshotKind.entries.sumOf { kind ->
        SnapshotSummary.State.entries.sumOf { of(kind).held(it) }
    }

    @Test
    fun `every entry held is counted once, in the state of its origin, and the states sum to the entries`() {
        val store = ControllableStore()
        store.put(SnapshotKey.Platform, document(1))
        store.put(SnapshotKey.App("ok"), document(2))
        store.put(SnapshotKey.App("broken"), document(3, "THROW"))
        store.put(SnapshotKey.Tenant("gone"), document(4))
        source(store).use { source ->
            source.summary().takenAt.assert().isNull()
            source.summary().total().assert().isEqualTo(0)
            source.load().join()
            source.reload(SnapshotKey.App("ok")).join()
            source.reload(SnapshotKey.App("broken")).join()
            source.reload(SnapshotKey.App("missing")).join()
            source.reload(SnapshotKey.Tenant("gone")).join()
            store.remove(SnapshotKey.Tenant("gone"))
            source.reload(SnapshotKey.Tenant("gone")).join()
            store.put(SnapshotKey.Tenant("garbled"), document(5))
            store.undecodable = true
            source.reload(SnapshotKey.Tenant("garbled")).join()
            store.undecodable = false

            source.reportLag()
            val summary = source.summary()
            summary.takenAt.assert().isNotNull()
            summary.total().assert().isEqualTo(source.entries().size)
            val app = summary.of(SnapshotKind.APP)
            app.held(SnapshotSummary.State.PRESENT).assert().isEqualTo(1)
            app.held(SnapshotSummary.State.INVALID).assert().isEqualTo(1)
            app.held(SnapshotSummary.State.ABSENT).assert().isEqualTo(1)
            val tenant = summary.of(SnapshotKind.TENANT)
            tenant.held(SnapshotSummary.State.LOST).assert().isEqualTo(1)
            tenant.held(SnapshotSummary.State.UNDECODABLE).assert().isEqualTo(1)
            summary.of(SnapshotKind.PLATFORM).held(SnapshotSummary.State.PRESENT).assert().isEqualTo(1)
        }
    }

    @Test
    fun `every origin an entry can have has its own state`() {
        val states = EntryOrigin.entries.map { SnapshotSummary.State.of(it) }
        states.toSet().size.assert().isEqualTo(EntryOrigin.entries.size)
        states.toSet().assert().isEqualTo(SnapshotSummary.State.entries.toSet())
    }

    @Test
    fun `the largest lag and the documents past the thresholds are as of the pass`() {
        val store = ControllableStore()
        store.put(SnapshotKey.Platform, document(1))
        store.put(SnapshotKey.App("a"), document(2))
        source(store).use { source ->
            source.load().join()
            source.reload(SnapshotKey.App("a")).join()
            clock.advance(options.lagWarn.plusSeconds(1))
            source.reportLag()
            val app = source.summary().of(SnapshotKind.APP)
            app.maxLag.assert().isGreaterThan(options.lagWarn)
            app.lagging.assert().isEqualTo(1)
            app.escalated.assert().isEqualTo(0)
            clock.advance(options.lagEscalate)
            source.reportLag()
            source.summary().of(SnapshotKind.APP).escalated.assert().isEqualTo(1)
            source.summary().of(SnapshotKind.APP).maxLag.assert().isGreaterThan(Duration.ZERO)
        }
    }

    @Test
    fun `an eviction on a slot is counted by kind and by what the key held`() {
        evictionsOn(SlotFactory.SLOT)
    }

    @Test
    fun `an eviction on a runner is counted the same way`() {
        evictionsOn(keyRunners())
    }

    private fun evictionsOn(slots: SlotFactory) {
        val store = ControllableStore()
        store.put(SnapshotKey.Platform, document(1))
        store.put(SnapshotKey.App("a"), document(2))
        val metrics = Evictions()
        source(store, metrics, slots).use { source ->
            source.load().join()
            source.reload(SnapshotKey.App("a")).join()
            source.reload(SnapshotKey.App("missing")).join()
            source.evict(SnapshotKey.App("a")).assert().isTrue()
            source.evict(SnapshotKey.App("missing")).assert().isTrue()
            metrics.evicted.toList().assert().containsExactly(SnapshotKind.APP to false, SnapshotKind.APP to true)
        }
    }
}
