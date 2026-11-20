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

/**
 * `SNP-14` at a bound larger than [SnapshotFamilies.INSTALL_EVICTIONS]: an install that takes its family over the cap
 * evicts at most that many entries of its kind on its own thread, which takes the family back within the cap, and leaves
 * the rest to housekeeping, which takes it to the bound. The installing thread may be the store client's (an absent
 * entry is not compiled): it never runs a whole pass back to a bound of tens of thousands.
 */
class InstallEvictionBoundTest {
    private val threads = SourceModelThreads()
    private val store = InMemorySnapshotDocumentStore()

    private fun source(options: SnapshotOptions) = SnapshotSource(
        store,
        SnapshotKey.Platform,
        FakeCompiler(),
        listOf(SnapshotFamily(SnapshotKind.APP, FakeCompiler(), options.maxApps, options.maxAbsentApps)),
        options,
        ManualClock(),
        SnapshotMetrics.NOOP,
        directExecutor,
        null,
        { 0L },
        threads.housekeeping,
    )

    @Test
    fun `an install over the cap evicts a bounded batch in place, and housekeeping takes the family to its bound`() {
        store.put(SnapshotKey.Platform, document(1))
        (0 until CAP + BATCHES * BATCH).forEach { store.put(SnapshotKey.App("app-$it"), document(1)) }
        source(SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = BOUND)).use { source ->
            source.load().join()
            (0 until CAP).forEach { source.load("app-$it").join() }
            source.heldCounts(SnapshotKind.APP).first.assert().isEqualTo(CAP)
            // Housekeeping is held: each install over the cap evicts on this thread, and only its batch.
            repeat(BATCHES) { batch ->
                val first = CAP + batch * BATCH
                source.load("app-$first").join()
                source.heldCounts(SnapshotKind.APP).first.assert().describedAs("after app-$first")
                    .isEqualTo(CAP + 1 - BATCH)
                // The installs that follow stay within the cap: nothing more is evicted in place.
                (first + 1 until first + BATCH).forEach { source.load("app-$it").join() }
                source.heldCounts(SnapshotKind.APP).first.assert().isEqualTo(CAP)
            }
            while (threads.housekeeping.tasks.isNotEmpty()) threads.runHousekeeping(0)
            source.heldCounts(SnapshotKind.APP).first.assert().isEqualTo(BOUND)
        }
    }

    @Test
    fun `absent entries over the cap are evicted the same way, on the thread that installs them`() {
        store.put(SnapshotKey.Platform, document(1))
        source(SnapshotOptions(revalidateInterval = QUIET_PASS, maxAbsentApps = BOUND)).use { source ->
            source.load().join()
            (0 until CAP + 1).forEach { source.load("made-up-$it").join() }
            source.heldCounts(SnapshotKind.APP).second.assert().isEqualTo(CAP + 1 - BATCH)
            source.heldAbsentMembers(SnapshotKind.APP).size.assert().isEqualTo(CAP + 1 - BATCH)
            while (threads.housekeeping.tasks.isNotEmpty()) threads.runHousekeeping(0)
            source.heldCounts(SnapshotKind.APP).second.assert().isEqualTo(BOUND)
        }
    }

    private companion object {
        const val BOUND = 1000
        const val CAP = 2 * BOUND
        const val BATCH = SnapshotFamilies.INSTALL_EVICTIONS
        const val BATCHES = 3
    }
}
