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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `CTL-72`, `CTL-41`: the control plane's cadences and limits are constants or computed, and each is held to what makes it
 * safe: a record is an orphan only once it has outlived a whole reclaim pass, and the shadow pass is a fifteenth of the
 * least a window lives.
 */
class ControlDerivedTest {
    /** CTL-85: the work budget is an eighth of the heap, and the startup line says what the largest body costs. */
    @Test
    fun `the work budget is an eighth of the heap, and the line says how many of the largest bodies fit`() {
        val mib = 1024L * 1024
        ControlDerived.workBudgetBytes(2_048 * mib).assert().isEqualTo(256 * mib)
        ControlDerived.workBudget(heap = 512 * mib).capacity.assert().isEqualTo(64 * mib)
        ControlDerived.bodyWeight(ControlDerived.MAX_BODY_BYTES).assert()
            .isEqualTo(ControlDerived.MAX_BODY_BYTES * (ControlDerived.BODY_TREE_FACTOR + ControlDerived.CANDIDATE_FACTOR))
        // On 512 MiB the largest body weighs more than the budget: it runs alone, one at a time.
        ControlDerived.describeWork(512 * mib).assert().contains("work budget=64MB (1/8 of the heap)").contains(": 1 at once")
        ControlDerived.describe(Duration.ofMinutes(15)).assert().contains("work budget=")
    }

    @Test
    fun `an orphan is removed only once it has outlived a whole reclaim pass`() {
        ControlDerived.ORPHAN_AGE.assert().isGreaterThanOrEqualTo(ControlDerived.RECLAIM_INTERVAL)
        ControlDerived.RECLAIM_INTERVAL.assert().isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun `the shadow pass is a fifteenth of the least a window lives, within a quarter second and a minute`() {
        ControlDerived.shadowCheckInterval(Duration.ofMinutes(15)).assert().isEqualTo(Duration.ofMinutes(1))
        ControlDerived.shadowCheckInterval(Duration.ofSeconds(5)).toMillis().assert().isEqualTo(333L)
        ControlDerived.shadowCheckInterval(Duration.ZERO).assert().isEqualTo(ControlDerived.SHADOW_CHECK_FLOOR)
        ControlDerived.shadowCheckInterval(Duration.ofHours(24)).assert().isEqualTo(ControlDerived.SHADOW_CHECK_CEILING)
    }

    @Test
    fun `a window never closes later than one pass after it could`() {
        for (minutes in listOf(1L, 15L, 120L)) {
            val minDuration = Duration.ofMinutes(minutes)
            ControlDerived.shadowCheckInterval(minDuration).assert()
                .isLessThanOrEqualTo(minDuration.dividedBy(ControlDerived.SHADOW_CHECKS_PER_MIN_DURATION.toLong()))
                .isLessThanOrEqualTo(Duration.ofMinutes(1))
        }
    }

    @Test
    fun `the body limit is the document cap and two megabytes, so it follows the cap`() {
        ControlDerived.MAX_BODY_BYTES.assert()
            .isEqualTo(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong() + ControlDerived.BODY_ROOM_BYTES)
        ControlDerived.MAX_BODY_BYTES.assert().isEqualTo(10L * 1024 * 1024)
        ChangeOptions().maxDocumentBytes.assert().isEqualTo(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES)
    }

    /** DR1-F-5: the control plane's document cache is one of three holders of the documents' quarter, not a share of its own. */
    @Test
    fun `the document cache is a third of the documents' share, beside the two snapshot sources`() {
        val budget = me.ahoo.cosec.runtime.HeapBudget.of(2L shl 30)
        ControlDerived.DOCUMENT_HOLDERS.assert().isEqualTo(3)
        ControlDerived.documentCacheBytes(budget).assert().isEqualTo(budget.documents / 3)
        // At the production overlay's 2 GiB heap, more than the two fixed caches of 64 MB it replaces held together.
        ControlDerived.documentCacheBytes(budget).assert().isGreaterThan(128L shl 20)
        ControlDerived.describe(Duration.ofMinutes(15)).assert()
            .contains("document cache=${ControlDerived.documentCacheBytes() shr 20}MB (1/3 of the documents' share)")
    }

    @Test
    fun `what was derived is said in the words of the formulas`() {
        ControlDerived.describe(Duration.ofMinutes(15)).assert()
            .contains("reclaim-interval=PT1H (constant)")
            .contains("orphan-age=PT1H (one reclaim pass)")
            .contains("shadow check interval=PT1M (1/15 of cosec.control.shadow.min-duration PT15M)")
            .contains("max body=10MB (the 8MB document cap and 2MB)")
    }
}
