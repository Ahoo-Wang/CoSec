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

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `SNP-25`: every instance that reads the store reports its status, with the release it runs, whether it shadows or not.
 * Without shadowing the reporter's own loop writes `shadows: false` and no windows; with it, the shadow reporter drives
 * the same reporter once a cycle and the loop stays quiet, so one instance writes one status under one id. A write that
 * fails never throws: it is counted, and the instance keeps serving.
 */
class InstanceReporterTest {
    private val store = InMemoryShadowReports()

    @Test
    fun `an instance that does not shadow reports its status and release, and no window`() = runBlocking<Unit> {
        val reporter = InstanceReporter("iam-1", store)
        reporter.report(windows = null, shadows = false).assert().isTrue()
        val status = store.statuses().single()
        status.instance.assert().isEqualTo("iam-1")
        status.shadows.assert().isFalse()
        status.windows.assert().isNull()
        status.release.assert().isNotNull().isEqualTo(InstanceStatus.RELEASE)
        reporter.lastWritten.assert().isEqualTo(status.at)
        // An instance that holds no documents (a service behind a gateway) still has a resident record: none held.
        store.residents(setOf("iam-1")).residents.getValue("iam-1").documents.assert().isEmpty()
    }

    @Test
    fun `the loop reports on its own, and stays quiet while the shadow reporter drives it`() = runBlocking<Unit> {
        val reporter = InstanceReporter("gw-1", store)
        val loop = async { reporter.run(Duration.ofMillis(5)) }
        delay(50)
        store.statuses().map { it.instance }.assert().containsExactly("gw-1")
        val shadow = ShadowReporter(ShadowRecorder("gw-1"), store, { mapOf(SnapshotKey.Platform to 3L) }, status = reporter)
        shadow.instanceReporter.assert().isSameAs(reporter)
        reporter.drivenByShadows.assert().isTrue()
        val before = reporter.lastWritten
        delay(50)
        reporter.lastWritten.assert().isEqualTo(before)
        loop.cancel()
        shadow.flush()
        store.statuses().single().shadows.assert().isFalse()
        store.residents(setOf("gw-1")).residents.getValue("gw-1").documents.assert()
            .isEqualTo(mapOf(SnapshotKey.Platform to 3L))
    }

    @Test
    fun `a write that fails is counted and never thrown`() = runBlocking<Unit> {
        val failing = object : ShadowReportSink {
            override suspend fun write(report: ShadowReport) = Unit

            override suspend fun write(status: InstanceStatus) = error("NOPERM this user has no permissions to access one of the keys")

            override suspend fun write(resident: ResidentDocuments) = error("NOPERM")
        }
        val reporter = InstanceReporter("gw-2", failing)
        reporter.report(windows = null, shadows = false).assert().isFalse()
        reporter.report(windows = null, shadows = false).assert().isFalse()
        reporter.failedWrites.get().assert().isEqualTo(4L)
        reporter.failingSince.assert().isNotNull()
        reporter.lastWritten.assert().isNull()
    }
}
