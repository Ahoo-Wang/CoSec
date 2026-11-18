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
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * A family lists the slots holding an absent entry beside its counts (RFC 0013 B2b §4c), so that the absent kind's
 * pass costs what that kind holds: over a family of [DOCUMENTS] documents it reads the stamps of its absent entries
 * alone, while the documents' pass reads the documents'. Housekeeping is a queue the test runs itself, so nothing is
 * evicted behind its back.
 */
class SnapshotFamilyMembershipTest {
    private val threads = SourceModelThreads()
    private val store = InMemorySnapshotDocumentStore()
    private val compiler = FakeCompiler()

    private fun source(options: SnapshotOptions) = SnapshotSource(
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
    ).also {
        store.put(SnapshotKey.Platform, document(1))
        it.load().join()
    }

    private fun madeUp(range: IntRange): Set<SnapshotKey> = range.mapTo(HashSet()) { SnapshotKey.App("made-up-$it") }

    @Test
    fun `an absent pass over a large family reads its absent entries alone`() {
        val options = SnapshotOptions(maxApps = DOCUMENTS)
        val source = source(options)
        (0 until DOCUMENTS).forEach { store.put(SnapshotKey.App("app-$it"), document(1)) }
        (0 until DOCUMENTS).forEach { source.load("app-$it").join() }
        (0 until ABSENT).forEach { source.load("made-up-$it").join() }
        source.heldCounts(SnapshotKind.APP).assert().isEqualTo(DOCUMENTS to ABSENT)
        source.heldAbsentMembers(SnapshotKind.APP).assert().isEqualTo(madeUp(0 until ABSENT))
        val excess = ABSENT - options.maxAbsent
        source.evictionVisits(SnapshotKind.APP, absent = true, wanted = excess).assert().isEqualTo(ABSENT)
        source.evictionVisits(SnapshotKind.APP, absent = false, wanted = 1).assert().isEqualTo(DOCUMENTS)
    }

    @Test
    fun `absent entries evicted beyond their bound leave the set, and a document installed in place of one leaves it too`() {
        val source = source(SnapshotOptions(maxAbsent = ABSENT_BOUND))
        (0 until 2 * ABSENT_BOUND).forEach { source.load("made-up-$it").join() }
        source.heldAbsentMembers(SnapshotKind.APP).assert().isEqualTo(madeUp(0 until 2 * ABSENT_BOUND))
        // Housekeeping's passes: the least recently used absent entries go, by claims.
        while (threads.housekeeping.tasks.isNotEmpty()) threads.runHousekeeping(0)
        source.heldCounts(SnapshotKind.APP).assert().isEqualTo(0 to ABSENT_BOUND)
        val left = source.entries().filter { it.key is SnapshotKey.App }.mapTo(HashSet()) { it.key }
        left.size.assert().isEqualTo(ABSENT_BOUND)
        source.heldAbsentMembers(SnapshotKind.APP).assert().isEqualTo(left)
        // The store gains one of them: a document now, no longer an absent entry.
        val gained = left.first() as SnapshotKey.App
        store.put(gained, document(1))
        source.reload(gained).join()
        source.heldAbsentMembers(SnapshotKind.APP).assert().isEqualTo(left - gained)
        source.heldCounts(SnapshotKind.APP).assert().isEqualTo(1 to ABSENT_BOUND - 1)
    }

    private companion object {
        /** Many times [ABSENT]: the property, not the size; `AbsentScanBenchmark` measures 100,000. */
        const val DOCUMENTS = 5_000
        const val ABSENT = 300
        const val ABSENT_BOUND = 4
    }
}
