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
 * `CTL-70`, `CTL-41`: the control plane's cadences and limits are constants or computed, and each is held to what makes it
 * safe: a record is an orphan only once it has outlived a whole reclaim pass, and the shadow pass is a fifteenth of the
 * least a window lives.
 */
class ControlDerivedTest {
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

    @Test
    fun `what was derived is said in the words of the formulas`() {
        ControlDerived.describe(Duration.ofMinutes(15)).assert()
            .contains("reclaim-interval=PT1H (constant)")
            .contains("orphan-age=PT1H (one reclaim pass)")
            .contains("shadow check interval=PT1M (1/15 of cosec.control.shadow.min-duration PT15M)")
            .contains("max body=10MB (the 8MB document cap and 2MB)")
    }
}
