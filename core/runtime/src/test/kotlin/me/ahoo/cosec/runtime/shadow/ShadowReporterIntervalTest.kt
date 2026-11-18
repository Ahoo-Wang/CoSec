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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.shadow

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * The coroutine delay is in whole milliseconds, so an interval under one is refused where the reporter is built:
 * a positive one that truncated to zero would be a loop that never suspends (#1397).
 */
class ShadowReporterIntervalTest {
    private object Discarding : ShadowReportSink {
        override suspend fun write(report: ShadowReport) = Unit

        override suspend fun write(status: InstanceStatus) = Unit
    }

    private fun reporter(interval: Duration, sink: ShadowReportSink = Discarding) =
        ShadowReporter(ShadowRecorder("audit"), sink, { emptyMap() }, interval = interval)

    @Test
    fun `an interval under a millisecond is refused, as are zero and a negative one`() {
        listOf(Duration.ofNanos(500_000), Duration.ofNanos(1), Duration.ZERO, Duration.ofSeconds(-1)).forEach {
            assertThrows<IllegalArgumentException> { reporter(it) }.message.assert()
                .contains("at least one millisecond").contains(it.toString())
        }
    }

    @Test
    fun `a millisecond and longer are accepted`() {
        reporter(Duration.ofMillis(1))
        reporter(Duration.ofNanos(1_500_000))
        reporter(Duration.ofSeconds(10))
    }

    /** The smallest interval suspends at its first delay instead of writing in a loop. */
    @Test
    fun `the shortest interval suspends before it writes`() = runBlocking<Unit> {
        var writes = 0
        val sink = object : ShadowReportSink {
            override suspend fun write(report: ShadowReport) = Unit

            override suspend fun write(status: InstanceStatus) {
                writes++
                if (writes == 1000) currentCoroutineContext().cancel()
            }
        }
        val running = launch(start = CoroutineStart.UNDISPATCHED) { reporter(Duration.ofMillis(1), sink).run() }
        val beforeSuspension = writes

        running.cancelAndJoin()

        beforeSuspension.assert().isEqualTo(0)
    }
}
