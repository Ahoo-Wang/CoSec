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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * #1265: a history repair walks the chain before it writes the index, so reclamation may finish in between. What the
 * repair then writes is held against the index as it is at each attempt: a revision reclaimed meanwhile stays out.
 */
class HistoryRepairRaceTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** What runs once right before the next index write: another instance meanwhile. */
    private val store = object : ControlStore by memory {
        @Volatile
        var beforeIndex: (suspend () -> Unit)? = null

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
            beforeIndex?.let { race ->
                beforeIndex = null
                race()
            }
            return memory.putIndex(key, expected, index)
        }
    }

    /** The other instance: the production retention, over the real store. */
    private val other = Revisions(memory, clock)
    private val reader = Revisions(store, clock)

    /** [count] revisions of acme's document, a day apart, and an index lost; the entries it held. */
    private suspend fun publishedWithLostIndex(count: Int): List<RevisionEntry> {
        repeat(count) {
            other.publish(ACME, other.head(ACME), candidate(ACME), publication())
            clock.now = clock.now.plus(Duration.ofDays(1))
        }
        val lost = memory.index(ACME)!!
        memory.putIndex(ACME, lost.digest, RevisionIndex.EMPTY).assert().isTrue()
        return lost.value.entries
    }

    /** What a later reader lists, each revision with its record. */
    private suspend fun listed(): List<Long> {
        val entries = Revisions(memory, clock).history(ACME)
        entries.filter { memory.record(ACME, it.digest) == null }.assert().isEmpty()
        return entries.map { it.revision }
    }

    @Test
    fun `a repair that lost its write to reclamation does not bring the reclaimed revisions back - #1265`() =
        runBlocking<Unit> {
            publishedWithLostIndex(52)
            store.beforeIndex = { other.reclaim(ACME).records.size.assert().isEqualTo(2) }

            reader.history(ACME)

            listed().assert().isEqualTo((3L..52L).toList())
            memory.index(ACME)!!.value.floor.assert().isEqualTo(3L)
        }

    @Test
    fun `a revision reclamation kept stays listed through the repair - #1265`() = runBlocking<Unit> {
        val kept = publishedWithLostIndex(52).first { it.revision == 1L }.digest
        store.beforeIndex = { other.reclaim(ACME, keep = setOf(kept)).records.size.assert().isEqualTo(1) }

        reader.history(ACME)

        listed().assert().isEqualTo(listOf(1L) + (3L..52L).toList())
    }

    @Test
    fun `a repair nothing races still rebuilds the whole index`() = runBlocking<Unit> {
        publishedWithLostIndex(3)

        reader.history(ACME).map { it.revision }.assert().isEqualTo(listOf(1L, 2L, 3L))

        listed().assert().isEqualTo(listOf(1L, 2L, 3L))
    }
}
