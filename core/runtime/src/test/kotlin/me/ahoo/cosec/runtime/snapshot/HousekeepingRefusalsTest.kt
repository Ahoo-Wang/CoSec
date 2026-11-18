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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.IOException

/** The summary's own state (`SNP-19`): at most one summary per interval, only when something moved, texts bounded. */
class HousekeepingRefusalsTest {
    private val refusals = HousekeepingRefusals()

    @Test
    fun `a summary resets the counts, and the next waits for the interval and for something to move`() {
        refusals.summary(0).assert().isNull()
        refusals.refused(HousekeepingTask.EVICT, "first")
        refusals.refused(HousekeepingTask.EVICT, "second")

        refusals.summary(0).assert().isEqualTo(
            "Snapshot housekeeping since its last summary: refused 2 tasks while full (each dropped; drop runs in " +
                "place): evict 2 (last: \"second\")."
        )

        refusals.refused(HousekeepingTask.LOAD_LOG, "third")
        refusals.summary(HousekeepingRefusals.INTERVAL_NANOS - 1).assert().isNull()
        refusals.summary(HousekeepingRefusals.INTERVAL_NANOS).assert().contains("load-log 1 (last: \"third\")")
        refusals.summary(HousekeepingRefusals.INTERVAL_NANOS * 3).assert().isNull()
    }

    @Test
    fun `a line keeps its exception's class and message, no stack, and at most its bound`() {
        HousekeepingRefusals.line("Loading failed.", IOException("connection reset"))
            .assert().isEqualTo("Loading failed. — java.io.IOException: connection reset")
        HousekeepingRefusals.line("x".repeat(1_000), null).length.assert().isEqualTo(HousekeepingRefusals.MAX_LINE)
        refusals.refused(HousekeepingTask.DROP, "y".repeat(1_000))
        refusals.summary(0)!!.assert().contains("(last: \"${"y".repeat(HousekeepingRefusals.MAX_LINE)}\")")
    }

    @Test
    fun `meter failures alone are summarized`() {
        refusals.meterFailed("SnapshotMetrics.onLoad", IllegalStateException("meter down"))

        refusals.summary(0).assert().isEqualTo(
            "Snapshot housekeeping since its last summary: meter failures 1 (last: \"SnapshotMetrics.onLoad — " +
                "java.lang.IllegalStateException: meter down\")."
        )
    }
}
