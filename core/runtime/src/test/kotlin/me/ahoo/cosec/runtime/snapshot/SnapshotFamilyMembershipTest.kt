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
 * A family lists the slots of each kind beside its counts (RFC 0013 B2b §4c), so that a pass for a kind costs what
 * the kind holds: the absent kind's pass over a family of [DOCUMENTS] documents reads the stamps of its absent entries
 * alone. Housekeeping is a queue this test never runs, so nothing is evicted behind its back.
 */
class SnapshotFamilyMembershipTest {
    private val threads = SlotModelThreads()
    private val store = InMemorySnapshotDocumentStore()
    private val options = SnapshotOptions(maxApps = DOCUMENTS)
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

    @Test
    fun `an absent pass over a large family reads its absent entries alone`() {
        store.put(SnapshotKey.Platform, document(1))
        (0 until DOCUMENTS).forEach { store.put(SnapshotKey.App("app-$it"), document(1)) }
        source.load().join()
        (0 until DOCUMENTS).forEach { source.load("app-$it").join() }
        (0 until ABSENT).forEach { source.load("made-up-$it").join() }
        source.heldCounts(SnapshotKind.APP).assert().isEqualTo(DOCUMENTS to ABSENT)
        val (documents, absent) = source.heldMembers(SnapshotKind.APP)
        documents.size.assert().isEqualTo(DOCUMENTS)
        absent.assert().isEqualTo((0 until ABSENT).mapTo(HashSet()) { SnapshotKey.App("made-up-$it") })
        source.evictionVisits(
            SnapshotKind.APP,
            absent = true,
            wanted = ABSENT - options.maxAbsent
        ).assert().isEqualTo(ABSENT)
        source.evictionVisits(SnapshotKind.APP, absent = false, wanted = 1).assert().isEqualTo(DOCUMENTS)
    }

    private companion object {
        const val DOCUMENTS = 100_000
        const val ABSENT = 300
    }
}
