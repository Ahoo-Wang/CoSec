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

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** Reports and statuses in memory: for tests and single-instance deployments. */
@InternalCoSecApi
class InMemoryShadowReports(private val clock: Clock = Clock.systemUTC()) : ShadowReportSink, ShadowReportReader {
    private val reports = ConcurrentHashMap<String, ConcurrentHashMap<String, ShadowReport>>()
    private val statuses = ConcurrentHashMap<String, InstanceStatus>()
    private val residents = ConcurrentHashMap<String, ResidentDocuments>()

    override suspend fun write(report: ShadowReport) {
        reports.computeIfAbsent(report.window) { ConcurrentHashMap() }[report.instance] = report
    }

    override suspend fun write(status: InstanceStatus) {
        statuses[status.instance] = status
    }

    override suspend fun write(resident: ResidentDocuments) {
        residents[resident.instance] = resident
    }

    override suspend fun reports(window: String): List<ShadowReport> = reports[window]?.values?.toList().orEmpty()

    override suspend fun statuses(): List<InstanceStatus> {
        val since = clock.instant().minus(ShadowReportReader.STATUS_TTL)
        return statuses.values.filter { !it.at.isBefore(since) }
    }

    /** The resident records of [instances] written within [ShadowReportReader.RESIDENT_TTL]; any other is missing. */
    override suspend fun residents(instances: Set<String>): ResidentReadings {
        val since = clock.instant().minus(ShadowReportReader.RESIDENT_TTL)
        val found = instances.mapNotNull { residents[it] }.filter { !it.at.isBefore(since) }.associateBy { it.instance }
        return ResidentReadings(found, instances - found.keys)
    }

    /** Every recent status: one kept in memory is never one that does not decode. */
    override suspend fun statusReadings(): StatusReadings = StatusReadings(statuses(), emptySet())
}
