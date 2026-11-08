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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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
 *
 * A window is kept until [forget]: once its candidate is no longer staged and its last report is written, its
 * reporter forgets it (#1210). Samples of a forgotten window, which a candidate read before it was withdrawn may still
 * yield, are dropped for [endedRetention]: written as a new report, they would replace the window's last numbers.
 */
class ShadowRecorder(
    val instance: String,
    private val clock: Clock = Clock.systemUTC(),
    private val endedRetention: Duration = DEFAULT_ENDED_RETENTION,
) {
    private class Window(val key: SnapshotKey) {
        val counts = EnumMap<ShadowOutcome, Long>(ShadowOutcome::class.java)
        val samples = EnumMap<ShadowOutcome, MutableList<ShadowSample>>(ShadowOutcome::class.java)
        val bases = HashSet<Long>()
        var first: Instant? = null
        var last: Instant? = null

        /** Samples recorded so far: what [forget] compares with what was reported. */
        var recorded = 0L
        var forgotten = false
    }

    private val windows = ConcurrentHashMap<String, Window>()

    /** The windows forgotten, with when: their late samples are dropped until then plus [endedRetention]. */
    private val ended = ConcurrentHashMap<String, Instant>()

    init {
        require(ShadowReport.isInstanceId(instance)) {
            "An instance id has only letters, digits, '.', '_' and '-': [$instance]."
        }
        require(!endedRetention.isNegative) { "The retention of ended windows is not negative." }
    }

    /** A sample of [window], the candidate of [key]. Dropped when the window was forgotten. */
    fun record(key: SnapshotKey, window: String, outcome: ShadowOutcome, baseRevision: Long?, sample: ShadowSample?) {
        if (ended.containsKey(window)) {
            return
        }
        val now = clock.instant()
        val state = windows.computeIfAbsent(window) { Window(key) }
        synchronized(state) {
            if (state.forgotten || ended.containsKey(window)) {
                // Forgotten since the look above: this sample is of an ended window, and this may be a new state for it.
                windows.remove(window, state)
                return
            }
            state.counts.merge(outcome, 1L, Long::plus)
            baseRevision?.let(state.bases::add)
            if (sample != null) {
                val samples = state.samples.getOrPut(outcome) { ArrayList() }
                if (samples.size < ShadowReport.MAX_SAMPLES) samples += sample
            }
            if (state.first == null) state.first = now
            state.last = now
            state.recorded++
        }
    }

    /** This instance's report of each window seen so far. */
    fun reports(): List<ShadowReport> = windows().map { it.report }

    /** Each window seen and not forgotten: its document, its report now, and how many samples that report counts. */
    fun windows(): List<RecordedWindow> = windows.map { (window, state) ->
        synchronized(state) {
            RecordedWindow(
                state.key,
                ShadowReport(
                    window = window,
                    instance = instance,
                    counts = state.counts.toMap(),
                    samples = state.samples.mapValues { it.value.toList() },
                    baseRevisions = state.bases.toSet(),
                    first = state.first,
                    last = state.last,
                ),
                state.recorded,
            )
        }
    }

    /**
     * Forgets [window] if it has had no sample since its report of [recorded] samples: its candidate is gone and that
     * report was written. `false` when a sample came in meanwhile: the next report counts it, and is the one written
     * last. Later samples of the window are dropped.
     */
    fun forget(window: String, recorded: Long): Boolean {
        val state = windows[window] ?: return false
        val now = clock.instant()
        synchronized(state) {
            if (state.recorded != recorded) {
                return false
            }
            state.forgotten = true
            ended[window] = now
            windows.remove(window, state)
        }
        val expired = now.minus(endedRetention)
        ended.entries.removeIf { it.value.isBefore(expired) }
        return true
    }

    companion object {
        /** Longer than a candidate source caches a candidate, and than a sampled request takes to compare. */
        val DEFAULT_ENDED_RETENTION: Duration = Duration.ofHours(1)
    }
}

/** One window as [ShadowRecorder.windows] lists it: the document it is a candidate of, its report, and its samples. */
class RecordedWindow(val key: SnapshotKey, val report: ShadowReport, val recorded: Long)

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
        require(!interval.isNegative && !interval.isZero) { "The report interval is positive." }
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
}
