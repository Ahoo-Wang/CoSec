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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Acknowledgement
import me.ahoo.cosec.control.change.ShadowState
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `CTL-10` (design D2 §5): a shadow window passes only when every instance with a status in the last minute lists it in
 * that status's `windows`. An instance that does not send them (an older version, or one holding no staged list)
 * acknowledges nothing; the longest window still closes insufficient whatever the acknowledgements, a `CANDIDATE_ERROR`
 * still blocks at once, and an acknowledgement adds no sample.
 */
class ShadowAcknowledgementTest {
    private val clock = MutableClock()
    private val reports = InMemoryShadowReports(clock)

    @Volatile
    private var statusesFail = false

    private val reader = object : ShadowReportReader by reports {
        override suspend fun statuses(): List<InstanceStatus> {
            if (statusesFail) throw IllegalStateException("timeout")
            return reports.statuses()
        }
    }
    private val options = ShadowOptions(Duration.ofMinutes(15), 10, Duration.ofHours(24))
    private val windows = ShadowWindows(reader, options, clock)
    private val state = ShadowState(WINDOW, clock.instant())

    private suspend fun sampled(instance: String, outcome: ShadowOutcome = ShadowOutcome.SAME, count: Long = 10) =
        reports.write(ShadowReport(WINDOW, instance, mapOf(outcome to count), emptyMap(), setOf(1), null, null))

    private suspend fun status(instance: String, windows: Set<String>?) =
        reports.write(InstanceStatus(instance, clock.instant(), emptyMap(), windows = windows))

    /** Moves past the statuses' shared read, which is at most five seconds old. */
    private fun later(by: Duration = Duration.ofMinutes(15)) {
        clock.now = clock.now.plus(by)
    }

    private fun look() = runBlocking { windows.look(state, clock.instant()) }

    @Test
    fun `a window with enough samples stays open while a live instance has not acknowledged it`() = runBlocking<Unit> {
        later()
        sampled("i-1")
        status("i-1", setOf(WINDOW))
        status("i-2", setOf("another"))
        look().let {
            it.summary.assert().isNull()
            it.acknowledgement.assert().isEqualTo(Acknowledgement(1, listOf("i-2")))
        }
        later(Duration.ofSeconds(10))
        status("i-1", setOf(WINDOW))
        status("i-2", setOf(WINDOW, "another"))
        look().let {
            it.acknowledgement.assert().isEqualTo(Acknowledgement(2, emptyList()))
            it.summary!!.insufficient.assert().isFalse()
            it.summary!!.blocked.assert().isFalse()
        }
    }

    @Test
    fun `an instance whose status sends no windows acknowledges nothing`() = runBlocking<Unit> {
        later()
        sampled("i-1")
        status("i-1", setOf(WINDOW))
        status("old", null)
        look().let {
            it.summary.assert().isNull()
            it.acknowledgement.waitingFor.assert().containsExactly("old")
        }
    }

    @Test
    fun `an instance whose status is older than a minute is not waited for`() = runBlocking<Unit> {
        status("gone", null)
        later()
        sampled("i-1")
        status("i-1", setOf(WINDOW))
        look().let {
            it.acknowledgement.assert().isEqualTo(Acknowledgement(1, emptyList()))
            it.summary.assert().isNotNull()
        }
    }

    @Test
    fun `the longest window closes insufficient whatever the acknowledgements`() = runBlocking<Unit> {
        later(Duration.ofHours(24))
        sampled("i-1")
        status("i-1", setOf(WINDOW))
        status("old", null)
        look().summary!!.insufficient.assert().isTrue()
    }

    @Test
    fun `a CANDIDATE_ERROR blocks at once though no instance acknowledges`() = runBlocking<Unit> {
        sampled("i-1", ShadowOutcome.CANDIDATE_ERROR, 1)
        status("i-1", null)
        look().summary!!.blocked.assert().isTrue()
    }

    @Test
    fun `an acknowledgement adds no sample`() = runBlocking<Unit> {
        later()
        status("i-1", setOf(WINDOW))
        look().let {
            it.acknowledgement.assert().isEqualTo(Acknowledgement(1, emptyList()))
            it.summary.assert().isNull()
        }
    }

    @Test
    fun `statuses that cannot be read keep the window open`() {
        later()
        runBlocking {
            sampled("i-1")
            status("i-1", setOf(WINDOW))
        }
        statusesFail = true
        assertThrownBy<ControlStoreException> { look() }
    }

    private companion object {
        const val WINDOW = "c1@1"
    }
}
