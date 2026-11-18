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

/** A caller's thread spends at most K + 1 events, whatever runs in place on it (RFC 0013 B2b §3, amendment 7). */
class DrainBudgetTest {
    /** Spends until the budget says no, at most [cap] times; how many it got. */
    private fun spendAll(cap: Int = 100): Int {
        var spent = 0
        while (spent < cap && DrainBudget.spend()) spent++
        return spent
    }

    @Test
    fun `an in-place pass whose offers open budgets of their own does not refill the caller's`() {
        DrainBudget.within(K + 1) {
            DrainBudget.spend().assert().isTrue()
            DrainBudget.spend().assert().isTrue()
            // The pass, as an install over the cap runs it: each victim's offer is a top-level call inside it.
            DrainBudget.unbounded {
                repeat(VICTIMS) { DrainBudget.within(K + 1) { DrainBudget.spend() } }
                spendAll().assert().isEqualTo(100)
            }
            spendAll().assert().isEqualTo(K + 1 - 2)
        }
        DrainBudget.cell()[DrainBudget.ACTIVE].assert().isEqualTo(0L)
    }

    @Test
    fun `a nested call spends the outer budget and a thread outside any call has none`() {
        DrainBudget.within(K + 1) {
            DrainBudget.within(100) { spendAll() }.assert().isEqualTo(K + 1)
            DrainBudget.spend().assert().isFalse()
        }
        spendAll().assert().isEqualTo(100)
    }

    private companion object {
        const val K = 2
        const val VICTIMS = 3
    }
}
