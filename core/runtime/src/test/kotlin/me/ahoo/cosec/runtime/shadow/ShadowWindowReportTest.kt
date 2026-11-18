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
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/** A window that has counted nothing has nothing to report: both sides of the rule, on every run. */
class ShadowWindowReportTest {
    @Test
    fun `a window that has counted nothing reports nothing`() {
        ShadowRecorder.Window(SnapshotKey.Platform).reported("w", "i").assert().isNull()
    }

    @Test
    fun `a window that has counted reports its count under the window and the instance`() {
        val window = ShadowRecorder.Window(SnapshotKey.Platform)
        window.counts[ShadowOutcome.SAME] = 1L
        window.recorded = 1L
        window.first = Instant.EPOCH
        window.last = Instant.EPOCH
        val reported = window.reported("w", "i")!!
        reported.recorded.assert().isEqualTo(1L)
        reported.key.assert().isEqualTo(SnapshotKey.Platform)
        reported.report.window.assert().isEqualTo("w")
        reported.report.instance.assert().isEqualTo("i")
        reported.report.counts.assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L))
    }
}
