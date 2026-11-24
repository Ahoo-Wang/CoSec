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

package me.ahoo.cosec.control.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.InstanceReaders
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentReadings
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.shadow.StatusReadings
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * The instances' statuses as `propagation` reads them (R2C-12, `CTL-62`): read at most once per [maxAge] on this
 * control host, and one read shared by the calls that wait for it, since each read is every instance's status. A read
 * that fails is never kept and never answered from an older read: the call fails ([ControlStoreException], 503 by
 * `CTL-52`) and the next call reads again.
 *
 * A status that does not decode is not a failed read (`CTL-73`): its instance is in [Read.unreadable], and every other
 * status is read. [unreadable] counts each such status a read found.
 *
 * The statuses are small: liveness, `shadows` and the acknowledged windows. The documents each instance holds are its
 * resident record, read only by [residents], for `propagation` (`CTL-74`, `CTL-75`): never by the acknowledgement, and
 * so never on a shadow window's closing path. They too are read at most once per [maxAge], for the instances the
 * statuses found live.
 */
internal class InstanceStatuses(
    private val reports: ShadowReportReader,
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
) {
    /** What one read answered: the statuses that read, the instances whose status does not, and when it was read. */
    class Read(val statuses: List<InstanceStatus>, val unreadable: Set<String>, val asOf: Instant)

    /** The resident records of the instances a status read found live, and when they were read. */
    class Residents(val live: Set<String>, val readings: ResidentReadings, val asOf: Instant)

    private val shared = SharedRead(clock, maxAge) { load() }
    private val readersShared = SharedRead(clock, maxAge) { loadReaders() }

    /** The releases the readers ran at the last read, as [InstanceReaders.releases]; empty before one. */
    @Volatile
    var releases: Map<String, Int> = emptyMap()
        private set
    private val residentsShared = SharedRead(clock, maxAge) { loadResidents() }
    private val unreadableCount = AtomicLong()

    /** The statuses that did not decode, of every read so far (`cosec.control.shadow.status.unreadable`). */
    val unreadable: Long get() = unreadableCount.get()

    suspend fun read(): Read = shared.read().let { Read(it.value.statuses, it.value.unreadable, it.asOf) }

    /** The readers of the store (`SNP-25`): the statuses of the last hour, live or not, and those that do not decode. */
    suspend fun readers(): InstanceReaders = readersShared.read().let {
        InstanceReaders(it.value.statuses, it.value.unreadable, it.asOf)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadReaders(): StatusReadings {
        val readings = try {
            reports.readerReadings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw unavailable(e)
        }
        val now = InstanceReaders(readings.statuses, readings.unreadable, clock.instant()).releases
        val before = releases
        releases = now
        // A mixed deployment, seen from the newer side (RFC 0014 G3): said once each time the releases change.
        if (now != before && (now.size > 1 || before.size > 1)) {
            log.info {
                "Instances of ${now.size} release(s) read this store in the last hour: " +
                    now.entries.joinToString { (release, count) -> "$release ×$count" } + " (SNP-25)."
            }
        }
        return readings
    }

    /** The resident records of every live instance, one whose status does not decode included (`CTL-75`). */
    suspend fun residents(): Residents = residentsShared.read().let {
        Residents(
            it.value.first,
            it.value.second,
            it.asOf
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadResidents(): Pair<Set<String>, ResidentReadings> {
        val statuses = read()
        val live = statuses.statuses.mapTo(HashSet()) { it.instance } + statuses.unreadable
        val readings = try {
            reports.residents(live)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw unavailable(e)
        }
        return live to readings
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun load(): StatusReadings {
        val statuses = try {
            reports.statusReadings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw unavailable(e)
        }
        unreadableCount.addAndGet(statuses.unreadable.size.toLong())
        return statuses
    }

    /**
     * What a failed read is answered with: a status that does not decode stays what it is (a stored value, 500, as every
     * such read of the control plane), and anything else is the store not answering (503).
     */
    private fun unavailable(e: Exception): Exception = when (e) {
        is SnapshotFormatException, is ControlStoreException -> e
        else -> ControlStoreException("The instances' statuses or resident documents cannot be read", e)
    }

    companion object {
        /** How old an answer of `propagation` may be ([SharedRead.MAX_AGE]). */
        val MAX_AGE: Duration = SharedRead.MAX_AGE
    }
}
