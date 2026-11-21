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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The instances' statuses as `propagation` reads them (R2C-12, `CTL-62`): read at most once per [maxAge] on this
 * control host, and one read shared by the calls that wait for it, since each read is every instance's status. A read
 * that fails is never kept and never answered from an older read: the call fails ([ControlStoreException], 503 by
 * `CTL-52`) and the next call reads again.
 */
internal class InstanceStatuses(
    private val reports: ShadowReportReader,
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
) {
    /** What one read answered, and when it was read. */
    class Read(val statuses: List<InstanceStatus>, val asOf: Instant)

    private val lock = Mutex()

    @Volatile
    private var last: Read? = null

    suspend fun read(): Read = fresh() ?: lock.withLock { fresh() ?: load().also { last = it } }

    private fun fresh(): Read? = last?.takeIf { Duration.between(it.asOf, clock.instant()) < maxAge }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun load(): Read {
        val asOf = clock.instant()
        val statuses = try {
            reports.statuses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw unavailable(e)
        }
        return Read(statuses, asOf)
    }

    /**
     * What a failed read is answered with: a status that does not decode stays what it is (a stored value, 500, as every
     * such read of the control plane), and anything else is the store not answering (503).
     */
    private fun unavailable(e: Exception): Exception = when (e) {
        is SnapshotFormatException, is ControlStoreException -> e
        else -> ControlStoreException("The instances' statuses cannot be read", e)
    }

    companion object {
        /** How old an answer of `propagation` may be: well under the minute after which a status is ignored. */
        val MAX_AGE: Duration = Duration.ofSeconds(5)
    }
}
