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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ResidentDocuments
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Writes this instance's [InstanceStatus] and its [ResidentDocuments] (RFC 0014 R5, `SNP-25`): every CoSec process
 * that reads the shared store reports, whether it shadows or not, so that the control plane sees every reader and the
 * release each runs. With shadowing on, [ShadowReporter] calls [report] once a cycle, after its reports, with the windows
 * it acknowledges; without, [run] reports every [interval] on its own. The host makes the first report as it starts,
 * before this instance reads any document.
 *
 * A write that fails never stops the instance: the status is what a publish waits for, never what a request is decided
 * on. The first failure is logged at ERROR, naming the keys the instance's Redis user must be allowed to write; later
 * ones at most once a minute, and every one counts in [failedWrites].
 */
@InternalCoSecApi
class InstanceReporter(
    /** This instance's id for this start: its status and resident keys, and the shadow recorder's (they are one). */
    val instance: String,
    private val sink: ShadowReportSink,
    /**
     * The documents this instance holds, with the revision of each: none until the host hands it the snapshot source's
     * (it reports before that source exists, so as to report before reading a document).
     */
    @Volatile var installed: () -> Map<SnapshotKey, Long> = { emptyMap() },
    private val clock: Clock = Clock.systemUTC(),
    /** The keys the instance writes, as its Redis user must allow them; named in the log when a write fails. */
    private val keys: String = "the instance's status and resident keys",
) {
    /** Whether [ShadowReporter] drives the reports (shadowing on): [run] then writes nothing of its own. */
    @Volatile
    var drivenByShadows: Boolean = false

    /** Writes that failed since the start. */
    val failedWrites: AtomicLong = AtomicLong()

    /** When the last status was written; null before one was. */
    @Volatile
    var lastWritten: Instant? = null
        private set

    /** Since when every status write has failed; null while the last one succeeded, or none was tried. */
    @Volatile
    var failingSince: Instant? = null
        private set

    private var lastResident: Map<SnapshotKey, Long>? = null
    private var residentAt: Instant? = null
    private var reported = false
    private var warnedAt: Instant? = null

    /**
     * One report: the resident documents when they changed or are half their life old, then the status, with the
     * shadow windows this instance acknowledges ([InstanceStatus.windows]) and whether it shadows. True when the status
     * was written.
     */
    suspend fun report(windows: Set<String>?, shadows: Boolean): Boolean {
        writeResident()
        val now = clock.instant()
        val written = attempt("the instance status") {
            sink.write(InstanceStatus(instance, now, windows = windows, shadows = shadows))
        }
        if (written) {
            lastWritten = now
            failingSince = null
        } else if (failingSince == null) {
            failingSince = now
        }
        return written
    }

    /** Writes the resident documents when they changed since the last write that succeeded, or that write is half its life old. */
    private suspend fun writeResident() {
        val now = clock.instant()
        val holding = installed()
        val due = residentAt?.let { !now.isBefore(it.plus(ShadowReportReader.RESIDENT_TTL.dividedBy(2))) } ?: true
        if (holding == lastResident && !due) return
        if (attempt("the resident documents") { sink.write(ResidentDocuments(instance, now, holding)) }) {
            lastResident = holding
            residentAt = now
        }
    }

    /**
     * Reports every [interval] until the caller's scope is cancelled, unless [ShadowReporter] drives the reports. The
     * host has made the first report already.
     */
    suspend fun run(interval: Duration = INTERVAL) {
        require(interval >= ShadowReporter.MIN_INTERVAL) { "The report interval is at least one millisecond, not $interval." }
        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())
            if (!drivenByShadows) report(windows = null, shadows = false)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun attempt(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        // A store's cancelled future is a failed write; only this caller's own cancellation ends the loop.
        currentCoroutineContext().ensureActive()
        failed(what, e)
        false
    } catch (e: Exception) {
        failed(what, e)
        false
    }

    private fun failed(what: String, e: Exception) {
        failedWrites.incrementAndGet()
        val now = clock.instant()
        if (!reported) {
            reported = true
            warnedAt = now
            log.error(e) {
                "Writing $what of instance [$instance] failed: the control plane cannot see this instance among the " +
                    "readers of the store, so a publish would not wait for it (SNP-25). Allow its Redis user to write " +
                    "$keys. This instance keeps serving; it writes again every ${INTERVAL.seconds} s."
            }
            return
        }
        if (warnedAt?.let { now.isBefore(it.plus(WARN_EVERY)) } == true) return
        warnedAt = now
        log.warn(e) { "Writing $what of instance [$instance] failed (${failedWrites.get()} failed writes so far)." }
    }

    @InternalCoSecApi
    companion object {
        /**
         * How often an instance that does not shadow reports: a [ShadowReporter.REPORTS_PER_STATUS]th of the time a
         * status counts as live ([ShadowReportReader.STATUS_TTL]), so that one lost write never makes it look gone.
         */
        val INTERVAL: Duration = ShadowReportReader.STATUS_TTL.dividedBy(ShadowReporter.REPORTS_PER_STATUS.toLong())

        private val WARN_EVERY: Duration = Duration.ofMinutes(1)
    }
}
