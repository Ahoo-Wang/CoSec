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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `STA-12`: every share of the heap CoSec takes, in one table, together under its bound, and none below its old default. */
class HeapBudgetTest {
    @Test
    fun `the shares together take at most the bound, leaving the documents their reserved quarter`() {
        // As fractions of the heap, to the 64th: the bound is 32/64.
        val sixtyFourths = HeapBudget.SHARES.values.sumOf { 64 / it }
        (sixtyFourths * HeapBudget.SUM_BOUND).assert().describedAs("${HeapBudget.SHARES}").isLessThanOrEqualTo(64L)
        val inexact = HeapBudget.SHARES.filterValues { 64 % it != 0L }
        inexact.assert().describedAs("shares the sum cannot count exactly").isEmpty()
        HeapBudget.SHARES.keys.assert().contains("documents", "change listener queues")
        HeapBudget.DOCUMENTS_SHARE.assert().isEqualTo(4L)
    }

    @Test
    fun `at the production overlay's 2 GiB heap no bound is below the count it replaced`() {
        val budget = HeapBudget.of(TWO_GIB)
        HeapBudget.count(budget.revocationTables, TOKEN_ENTRY + SUBJECT_ENTRY).assert().isEqualTo(516_222)
            .isGreaterThanOrEqualTo(500_000)
        budget.tokenCache.assert().isGreaterThanOrEqualTo(16_384L * LARGE_TOKEN_ENTRY)
        // The starter's default listeners: metrics, audit log, Kafka audit, shadow, all BEST_EFFORT.
        budget.decisionQueueCapacity(4).assert().isEqualTo(8_192).isGreaterThanOrEqualTo(1_024)
        budget.decisionQueueCapacity(32).assert().isGreaterThanOrEqualTo(1_024)
        budget.changeListeners.assert().isEqualTo(64L shl 20)
    }

    @Test
    fun `a 512 MiB heap gets a quarter of each, and never nothing`() {
        val budget = HeapBudget.of(TWO_GIB / 4)
        budget.revocationTables.assert().isEqualTo(64L shl 20)
        HeapBudget.count(budget.revocationTables, TOKEN_ENTRY + SUBJECT_ENTRY).assert().isEqualTo(129_055)
        budget.tokenCache.assert().isEqualTo(32L shl 20)
        budget.decisionQueueCapacity(4).assert().isEqualTo(2_048)
        HeapBudget.of(1).decisionQueueCapacity(0).assert().isEqualTo(1)
        HeapBudget.count(Long.MAX_VALUE, 1).assert().isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `the startup line names each budget and its share`() {
        HeapBudget.of(TWO_GIB).toString().assert()
            .contains("heap 2048 MiB (Runtime.maxMemory())")
            .contains("revocation tables=256 MiB (1/8)")
            .contains("token cache=128 MiB (1/16)")
            .contains("decision listener queues=32 MiB (1/64)")
            .contains("change listener queues=64 MiB (1/32)")
            .contains("reserved for documents=512 MiB (1/4)")
    }

    private companion object {
        const val TWO_GIB = 2L shl 30
        const val TOKEN_ENTRY = 290L
        const val SUBJECT_ENTRY = 230L

        /** What the old default's large token weighed: 1,617 characters at 5 bytes (`VerifiedTokenCacheSizeTest`). */
        const val LARGE_TOKEN_ENTRY = 1_617L * 5
    }
}
