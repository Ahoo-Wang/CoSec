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

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration

/** What the control plane reads back: every instance's report of a window, and the statuses written recently. */
@CoSecSpi
@ExperimentalCoSecApi
interface ShadowReportReader {
    /** Every instance's report of [window]; throws when one of them cannot be read, or the store does not answer. */
    suspend fun reports(window: String): List<ShadowReport>

    /**
     * Every instance's report of [window] that reads, and the instances whose report is listed and cannot be read
     * ([ShadowReadings.unreadable]): it is missing, or it does not decode. The control plane treats such an instance as
     * one whose evidence is missing, never as one that saw nothing (`CTL-56`). A store that does not answer is still a
     * failure of the whole call, thrown, never an unreadable report. The default reads [reports], so an implementation
     * that does not override it throws where [reports] throws and never names an unreadable report.
     */
    suspend fun readings(window: String): ShadowReadings = ShadowReadings(reports(window), emptySet())

    /** Statuses written within [STATUS_TTL] of now. */
    suspend fun statuses(): List<InstanceStatus>

    companion object {
        /** An instance that has not written its status for this long is not counted (RFC 0007 §6.5). */
        val STATUS_TTL: Duration = Duration.ofMinutes(1)
    }
}

/**
 * What [ShadowReportReader.readings] read of a window: the [reports] that read, and the ids of the instances whose
 * report is listed and [unreadable] (missing, or not decoding), which carry no counts.
 */
@ExperimentalCoSecApi
class ShadowReadings(val reports: List<ShadowReport>, val unreadable: Set<String>) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ShadowReadings && reports == other.reports && unreadable == other.unreadable

    override fun hashCode(): Int = 31 * reports.hashCode() + unreadable.hashCode()

    override fun toString(): String = "ShadowReadings(reports=$reports, unreadable=$unreadable)"
}
