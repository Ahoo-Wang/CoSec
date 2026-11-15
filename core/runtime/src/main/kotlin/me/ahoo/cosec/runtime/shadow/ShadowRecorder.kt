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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowSample
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Accumulates, in memory, what this instance's shadow listener saw of each window (RFC 0007 §6.4). Thread-safe;
 * [reports] makes this instance's cumulative report of every window it has seen.
 *
 * A window is kept until [forget]: once its candidate is no longer staged and its last report is written, its
 * reporter forgets it (#1210). Samples of a forgotten window, which a candidate read before it was withdrawn may still
 * yield, are dropped for [endedRetention]: written as a new report, they would replace the window's last numbers.
 */
@InternalCoSecApi
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

    @InternalCoSecApi
    companion object {
        /** Longer than a candidate source caches a candidate, and than a sampled request takes to compare. */
        val DEFAULT_ENDED_RETENTION: Duration = Duration.ofHours(1)
    }
}
