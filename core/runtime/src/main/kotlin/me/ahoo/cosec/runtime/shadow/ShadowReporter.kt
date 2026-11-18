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
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.InstalledDocument
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Writes this instance's shadow reports and its status every [interval] (RFC 0007 §6.4, §6.5): BEST_EFFORT, so a
 * failing [sink] costs one cycle of reports and never reaches a request. [installed] names the documents the instance
 * has installed, with their revisions and lag.
 *
 * With [candidates], the store of record the candidates are read from, a window whose document no longer stages its
 * candidate has ended: its report is written once more, with every sample so far, and the window is forgotten
 * (#1210). A window whose candidate cannot be read is kept, and so is one whose last write failed: the next cycle
 * writes it again. Without [candidates] every window is kept, and written every cycle.
 */
@InternalCoSecApi
class ShadowReporter(
    private val recorder: ShadowRecorder,
    private val sink: ShadowReportSink,
    private val installed: () -> Map<SnapshotKey, InstalledDocument>,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = Duration.ofSeconds(10),
    /** Where it reads whether a window's candidate is still staged; without it, every window is kept. */
    val candidates: CandidateStore? = null,
) {
    init {
        // `delay` takes whole milliseconds: a shorter interval would be 0, a loop that never suspends (#1397).
        require(interval >= MIN_INTERVAL) { "The report interval is at least one millisecond, not $interval." }
    }

    /** One cycle: every window's report, then the status. Failures are logged and skipped. */
    suspend fun flush() {
        val staged = HashMap<SnapshotKey, String?>()
        for (window in recorder.windows()) {
            // The reports were taken first, so a window among them was staged before this read and a candidate gone now
            // has really ended. A sample after its report is caught by forget, which compares what the report counted.
            val ended = ended(window, staged)
            val written = attempt("the shadow report of window ${window.report.window}") { sink.write(window.report) }
            if (ended && written) {
                recorder.forget(window.report.window, window.recorded)
            }
        }
        attempt("the instance status") { sink.write(InstanceStatus(recorder.instance, clock.instant(), installed())) }
    }

    /**
     * Whether [window]'s document stages another candidate or none: read once per document and cycle, from [candidates].
     * A read that fails is not an end.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ended(window: RecordedWindow, staged: MutableMap<SnapshotKey, String?>): Boolean {
        val store = candidates ?: return false
        val key = window.key
        if (key !in staged) {
            staged[key] = try {
                store.candidate(key)?.window
            } catch (e: CancellationException) {
                // A store's cancelled future is a failed read; only this caller's own cancellation ends the cycle.
                currentCoroutineContext().ensureActive()
                log.debug(e) { "Reading the candidate of [$key] was cancelled; its windows are kept." }
                return false
            } catch (e: Exception) {
                log.debug(e) { "Reading the candidate of [$key] failed; its windows are kept." }
                return false
            }
        }
        return staged[key] != window.report.window
    }

    /** Flushes every [interval] until the caller's scope is cancelled; the host launches it in its own scope. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())
            flush()
        }
    }

    /** Runs [block]; `false` when it failed, which is logged. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attempt(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        // As for a read: only this caller's own cancellation ends the reporter's loop.
        currentCoroutineContext().ensureActive()
        log.warn(e) { "Writing $what was cancelled; the next cycle writes it again." }
        false
    } catch (e: Exception) {
        log.warn(e) { "Writing $what failed; the next cycle writes it again." }
        false
    }

    @InternalCoSecApi
    companion object {
        /** The shortest interval: the coroutine delay is in whole milliseconds. */
        val MIN_INTERVAL: Duration = Duration.ofMillis(1)
    }
}
