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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `CTL-71`: an instance reports a sixth of its status's life apart, and no oftener than a fifth of the shortest life of a
 * window nor than a second; so it is never older than the status that makes it counted, and a window sees fresh evidence.
 */
class ShadowReporterDerivedIntervalTest {
    @Test
    fun `the default window gives the interval the setting used to have`() {
        ShadowReporter.derivedInterval(Duration.ofMinutes(15)).assert().isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun `a shorter window reports oftener, down to a second`() {
        ShadowReporter.derivedInterval(Duration.ofSeconds(5)).assert().isEqualTo(Duration.ofSeconds(1))
        ShadowReporter.derivedInterval(Duration.ofSeconds(20)).assert().isEqualTo(Duration.ofSeconds(4))
        ShadowReporter.derivedInterval(Duration.ZERO).assert().isEqualTo(ShadowReporter.MIN_DERIVED_INTERVAL)
    }

    @Test
    fun `the interval is never longer than a sixth of the status's life, so an instance is never uncounted for it`() {
        for (minutes in listOf(1L, 15L, 60L, 24L * 60)) {
            val interval = ShadowReporter.derivedInterval(Duration.ofMinutes(minutes))
            interval.multipliedBy(ShadowReporter.REPORTS_PER_STATUS.toLong())
                .assert().isLessThanOrEqualTo(ShadowReportReader.STATUS_TTL)
            interval.assert().isGreaterThanOrEqualTo(ShadowReporter.MIN_INTERVAL)
        }
    }
}
