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

package me.ahoo.cosec.runtime.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.ahoo.cosec.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Accumulates, in memory, what this instance's shadow listener saw of each window (RFC 0007 §6.4). Thread-safe;
 * [reports] makes this instance's cumulative report of every window it has seen.
 */
class ShadowRecorder(val instance: String, private val clock: Clock = Clock.systemUTC()) {
    private class Window {
        val counts = EnumMap<ShadowOutcome, Long>(ShadowOutcome::class.java)
        val samples = EnumMap<ShadowOutcome, MutableList<ShadowSample>>(ShadowOutcome::class.java)
        val bases = HashSet<Long>()
        var first: Instant? = null
        var last: Instant? = null
    }

    private val windows = ConcurrentHashMap<String, Window>()

    init {
        require(instance.isNotEmpty()) { "An instance has an id." }
    }

    fun record(window: String, outcome: ShadowOutcome, baseRevision: Long?, sample: ShadowSample?) {
        val now = clock.instant()
        val state = windows.computeIfAbsent(window) { Window() }
        synchronized(state) {
            state.counts.merge(outcome, 1L, Long::plus)
            baseRevision?.let(state.bases::add)
            if (sample != null) {
                val samples = state.samples.getOrPut(outcome) { ArrayList() }
                if (samples.size < ShadowReport.MAX_SAMPLES) samples += sample
            }
            if (state.first == null) state.first = now
            state.last = now
        }
    }

    /** This instance's report of each window seen so far. */
    fun reports(): List<ShadowReport> = windows.map { (window, state) ->
        synchronized(state) {
            ShadowReport(
                window = window,
                instance = instance,
                counts = state.counts.toMap(),
                samples = state.samples.mapValues { it.value.toList() },
                baseRevisions = state.bases.toSet(),
                first = state.first,
                last = state.last,
            )
        }
    }

    /** Forgets [window]: its candidate is gone, and its last report was written. */
    fun forget(window: String) {
        windows.remove(window)
    }
}

/**
 * Writes this instance's shadow reports and its status every [interval] (RFC 0007 §6.4, §6.5): BEST_EFFORT, so a
 * failing [sink] costs one cycle of reports and never reaches a request. [installed] names the documents the instance
 * has installed, with their revisions and lag.
 */
class ShadowReporter(
    private val recorder: ShadowRecorder,
    private val sink: ShadowReportSink,
    private val installed: () -> Map<SnapshotKey, InstalledDocument>,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = Duration.ofSeconds(10),
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "The report interval is positive." }
    }

    /** One cycle: every window's report, then the status. Failures are logged and skipped. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun flush() {
        for (report in recorder.reports()) {
            attempt("the shadow report of window ${report.window}") { sink.write(report) }
        }
        attempt("the instance status") { sink.write(InstanceStatus(recorder.instance, clock.instant(), installed())) }
    }

    /** Flushes every [interval] until the caller's scope is cancelled; the host launches it in its own scope. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())
            flush()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Writing $what failed; the next cycle writes it again." }
        }
    }
}
