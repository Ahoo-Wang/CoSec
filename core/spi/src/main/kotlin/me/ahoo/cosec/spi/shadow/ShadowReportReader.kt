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

    /**
     * The statuses written within [STATUS_TTL] of now that read, and the instances whose status is listed and does not
     * decode ([StatusReadings.unreadable]): a crash-looping version, a corrupt value, or a format this version does not
     * read during a rolling upgrade. The control plane takes such an instance as live and acknowledging no window, so no
     * window passes on it, while every other status is read (`CTL-73`). A status stored under another instance's key,
     * and a store that does not answer, still fail the whole call.
     *
     * The default reads [statuses]: an implementation that does not override it cannot tell a status that does not
     * decode from none, so it fails the read where [statuses] fails (every shadow window and `propagation` wait until
     * the status reads or expires) rather than dropping the status, which would let a window pass without that instance.
     */
    suspend fun statusReadings(): StatusReadings = StatusReadings(statuses(), emptySet())

    /**
     * The readers of the store (RFC 0014 R5, `SNP-25`): the statuses written within [READERS_TTL] of now that read, live
     * or not, and those listed that do not decode. What a publish will wait for (8.1), and what the control plane shows
     * of the instances and the releases they run. The default reads [statusReadings]: an implementation that keeps
     * statuses no longer than [STATUS_TTL] answers only the live ones.
     */
    suspend fun readerReadings(): StatusReadings = statusReadings()

    /**
     * The resident documents of [instances] (`CTL-75`): each one's record that reads, and in
     * [ResidentReadings.unreadable] those whose record is missing or does not decode. Only `propagation` calls it, for
     * the instances its status read found live; a window's closing path never does (`CTL-74`). A store that does not
     * answer throws, for the whole call.
     */
    suspend fun residents(instances: Set<String>): ResidentReadings

    companion object {
        /** An instance that has not written its status for this long is not counted (RFC 0007 §6.5). */
        val STATUS_TTL: Duration = Duration.ofMinutes(1)

        /**
         * How long a status is kept after its last write, and so how far back the readers of the store reach
         * ([readerReadings], `SNP-25`). The writers of 8.0 fix it: it is the upper bound of any window a later reader
         * may choose, while what counts as live stays [STATUS_TTL]. An hour covers an instance cut off from Redis that
         * keeps serving on its installed documents for that long, at the cost that the last instance of an old release
         * holds the readers back for up to an hour after it is gone; longer would hold every publish behind long-gone
         * instances, and no finite bound covers an instance cut off for longer (RFC 0014 §4).
         */
        val READERS_TTL: Duration = Duration.ofHours(1)

        /**
         * How long a resident record lives after its last write (`CTL-75`). An instance writes it again before half of
         * that has passed, so a live instance always has one; a gone one's is ignored at once (it has no live status)
         * and expires within this.
         */
        val RESIDENT_TTL: Duration = Duration.ofHours(1)
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

/**
 * What [ShadowReportReader.statusReadings] read: the recent [statuses] that read, and the ids of the instances whose
 * listed status does not decode ([unreadable]), which carry no documents and acknowledge no window.
 */
@ExperimentalCoSecApi
class StatusReadings(val statuses: List<InstanceStatus>, val unreadable: Set<String>) {
    override fun equals(other: Any?): Boolean =
        this === other || other is StatusReadings && statuses == other.statuses && unreadable == other.unreadable

    override fun hashCode(): Int = 31 * statuses.hashCode() + unreadable.hashCode()

    override fun toString(): String = "StatusReadings(statuses=$statuses, unreadable=$unreadable)"
}

/**
 * What [ShadowReportReader.residents] read: the [residents] that read, by instance, and the ids of the instances whose
 * record is missing or does not decode ([unreadable]), which `propagation` counts as `unknown`.
 */
@ExperimentalCoSecApi
class ResidentReadings(val residents: Map<String, ResidentDocuments>, val unreadable: Set<String>) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ResidentReadings && residents == other.residents && unreadable == other.unreadable

    override fun hashCode(): Int = 31 * residents.hashCode() + unreadable.hashCode()

    override fun toString(): String = "ResidentReadings(residents=$residents, unreadable=$unreadable)"
}
