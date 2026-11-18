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
import java.util.concurrent.atomic.AtomicInteger

/**
 * A window that has counted nothing has nothing to report: [ShadowRecorder] makes a window visible a moment before its
 * first sample is merged, and a report read in that moment counted nothing. One-sided: it can only fail when the rule is
 * broken, and then not on every run (a reader has to come between the two steps).
 */
class ShadowRecorderEmptyWindowTest {
    @Test
    fun `a report always counts what it reports`() {
        val empty = AtomicInteger()
        repeat(ROUNDS) {
            val recorder = ShadowRecorder("i")
            val reader = Thread {
                var seen = false
                while (!seen) {
                    val reports = recorder.reports()
                    if (reports.isNotEmpty()) {
                        seen = true
                        if (reports.any { it.counts.isEmpty() }) empty.incrementAndGet()
                    }
                    Thread.onSpinWait()
                }
            }
            reader.start()
            recorder.record(SnapshotKey.Platform, "w", ShadowOutcome.SAME, null, null)
            reader.join()
        }
        empty.get().assert().describedAs("rounds in which a report with no counts was read").isZero()
    }

    private companion object {
        const val ROUNDS = 5_000
    }
}
