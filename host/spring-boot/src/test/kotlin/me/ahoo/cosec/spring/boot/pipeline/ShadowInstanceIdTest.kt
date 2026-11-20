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

package me.ahoo.cosec.spring.boot.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.shadow.ShadowReporter
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * A configured `cosec.authorization.shadow.instance-id` is the prefix of an id new at every start: a process restarted
 * with the same configuration never replaces the reports its predecessor wrote, so a `CANDIDATE_ERROR` that process
 * recorded still blocks the window.
 */
class ShadowInstanceIdTest {
    private val tenant = SnapshotKey.Tenant("acme")

    @Test
    fun `a configured id is a prefix, with a suffix new at every start`() {
        val first = ShadowInstanceId.forThisStart("pod-a")
        val second = ShadowInstanceId.forThisStart("pod-a")
        first.assert().startsWith("pod-a.").isNotEqualTo(second)
        second.assert().startsWith("pod-a.")
        ShadowReport.isInstanceId(first).assert().isTrue()
        ShadowInstanceId.forThisStart("").assert().isNotEqualTo(ShadowInstanceId.forThisStart(""))
    }

    @Test
    fun `a restart with the same configured id keeps the earlier report`() = runBlocking<Unit> {
        val reports = InMemoryShadowReports()
        // The process before the restart could not compile the candidate, and reported it.
        val before = ShadowRecorder(ShadowInstanceId.forThisStart("pod-a"))
        before.record(tenant, WINDOW, ShadowOutcome.CANDIDATE_ERROR, 4, null)
        ShadowReporter(before, reports, { emptyMap() }).flush()
        // The same pod, restarted with the same configuration, samples requests that compare the same.
        val after = ShadowRecorder(ShadowInstanceId.forThisStart("pod-a"))
        after.record(tenant, WINDOW, ShadowOutcome.SAME, 4, null)
        ShadowReporter(after, reports, { emptyMap() }).flush()
        val read = reports.reports(WINDOW)
        read.map { it.instance }.toSet().assert().containsExactlyInAnyOrder(before.instance, after.instance)
        read.sumOf { it.counts[ShadowOutcome.CANDIDATE_ERROR] ?: 0L }.assert().isEqualTo(1L)
    }

    private companion object {
        const val WINDOW = "w-1"
    }
}
