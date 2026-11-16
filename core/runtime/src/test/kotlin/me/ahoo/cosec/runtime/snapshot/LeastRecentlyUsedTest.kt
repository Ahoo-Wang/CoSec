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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The eviction pass's choice on its own input ([SnapshotFamilies.leastRecentlyUsed]): the stamps a scan reads are
 * written by other threads while it runs, which no test can arrange from outside. So here every read of a stamp moves
 * it, and the choice must still be the least recently used as each was read, once, in order.
 */
class LeastRecentlyUsedTest {
    @Test
    fun `it returns the least recently used as each stamp was read, once, least recent first`() {
        val random = Random(SEED)
        val stamps = LongArray(SLOTS) { random.nextLong(1_000_000) }
        val firstRead = LongArray(SLOTS)
        val reads = IntArray(SLOTS)
        val chosen = SnapshotFamilies.leastRecentlyUsed(
            (0 until SLOTS).asSequence(),
            WANTED,
            { slot ->
                reads[slot]++
                stamps[slot].also {
                    if (reads[slot] == 1) firstRead[slot] = it
                    // A request touches the slot as soon as the scan has read it: any later read sees another order.
                    stamps[slot] = random.nextLong(1_000_000)
                }
            },
            { true },
        )
        reads.all { it == 1 }.assert().isTrue()
        val expected = firstRead.sorted().take(WANTED)
        chosen.map { firstRead[it] }.assert().isEqualTo(expected)
    }

    @Test
    fun `only a slot that would be chosen is asked whether it is idle, and a busy one is passed over`() {
        val asked = mutableListOf<Int>()
        // Stamps fall from 10 to 1: every slot would be chosen as it is reached; slot 5 is busy.
        val falling = SnapshotFamilies.leastRecentlyUsed((0 until 10).asSequence(), 3, { (10 - it).toLong() }) { slot ->
            asked += slot
            slot != 5
        }
        falling.assert().containsExactly(9, 8, 7)
        asked.assert().isEqualTo((0 until 10).toList())
        asked.clear()
        val rising = SnapshotFamilies.leastRecentlyUsed((0 until 10).asSequence(), 3, { it.toLong() }) { slot ->
            asked += slot
            true
        }
        rising.assert().containsExactly(0, 1, 2)
        // Once three are chosen, a slot used more recently than all of them is not asked.
        asked.assert().isEqualTo(listOf(0, 1, 2))
    }

    @Test
    fun `wanting none chooses none and reads nothing`() {
        val none = SnapshotFamilies.leastRecentlyUsed(
            (0 until 10).asSequence(),
            0,
            { error("read") }
        ) { error("asked") }
        none.assert().isEmpty()
    }

    private companion object {
        const val SEED = 14
        const val SLOTS = 500
        const val WANTED = 64
    }
}
