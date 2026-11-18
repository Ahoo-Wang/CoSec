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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.LoadOutcome
import me.ahoo.cosec.runtime.snapshot.SnapshotEntry
import me.ahoo.cosec.runtime.snapshot.SnapshotMetrics
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.runtime.snapshot.SnapshotSummary
import me.ahoo.cosec.runtime.snapshot.summary
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.time.Clock
import java.time.Duration

/**
 * The `cosec.snapshot.*` meters of a [SnapshotSource] (`wiki/operations/observability.md` holds their names). Every
 * tag has a closed value set, never an app or tenant id: `kind`, `result`, `state`, `level`, `to`.
 *
 * Every meter is resolved here, in the constructor, so an event is an array index and an increment. A hit is not
 * counted: it is not a load, and [onLoad] runs at every `peek`, the one path every request takes, where a shared count
 * cost 12 % of a peek's throughput on one thread and 45 % on eight (#1614). The share of requests that waited for a
 * document is `loads{result=cold}` over the decisions (`cosec.authorization`). A registry that refuses a meter fails
 * here, at startup, never on an event. The gauges ([bind]) read the [SnapshotSummary] of the source's last lag
 * pass, so a scrape walks nothing.
 */
@InternalCoSecApi
class MicrometerSnapshotMetrics(
    private val registry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : SnapshotMetrics {
    private val loads = Array(KINDS.size * LOADED.size) { i ->
        counter(LOADS, KIND, tag(KINDS[i / LOADED.size]), RESULT, tag(LOADED[i % LOADED.size]))
    }
    private val compiles = Array(KINDS.size * 2) { i ->
        Timer.builder(COMPILE_DURATION)
            .tags(KIND, tag(KINDS[i / 2]), RESULT, if (i % 2 == 0) "success" else "failure")
            .register(registry)
    }
    private val installs = Array(KINDS.size) { counter(INSTALLS, KIND, tag(KINDS[it])) }
    private val lagExceeded = Array(KINDS.size * 2) { i ->
        counter(LAG_EXCEEDED, KIND, tag(KINDS[i / 2]), LEVEL, if (i % 2 == 0) WARN else ESCALATE)
    }
    private val regressions = Array(KINDS.size) { counter(REGRESSIONS, KIND, tag(KINDS[it])) }
    private val lost = Array(KINDS.size) { counter(LOST, KIND, tag(KINDS[it])) }
    private val evictions = Array(KINDS.size * 2) { i ->
        counter(EVICTIONS, KIND, tag(KINDS[i / 2]), STATE, if (i % 2 == 0) "document" else "absent")
    }
    private val handOffs = Array(2) { counter(HANDOFFS_REFUSED, "to", if (it == 0) "housekeeping" else "caller") }

    private fun counter(name: String, vararg tags: String): Counter =
        Counter.builder(name).tags(*tags).register(registry)

    override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
        if (outcome == LoadOutcome.HIT) {
            return
        }
        loads[kind.ordinal * LOADED.size + LOADED.indexOf(outcome)].increment()
    }

    override fun onCompile(kind: SnapshotKind, duration: Duration, succeeded: Boolean) =
        compiles[kind.ordinal * 2 + if (succeeded) 0 else 1].record(duration)

    override fun onInstall(entry: SnapshotEntry<*>) = installs[entry.key.kind.ordinal].increment()

    override fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) =
        regressions[key.kind.ordinal].increment()

    override fun onDocumentLost(key: SnapshotKey, lastRevision: Long) = lost[key.kind.ordinal].increment()

    override fun onLagExceeded(key: SnapshotKey, lag: Duration, escalated: Boolean) =
        lagExceeded[key.kind.ordinal * 2 + if (escalated) 1 else 0].increment()

    override fun onHandOffRefused(ranInPlace: Boolean) = handOffs[if (ranInPlace) 1 else 0].increment()

    override fun onEvicted(kind: SnapshotKind, absent: Boolean) =
        evictions[kind.ordinal * 2 + if (absent) 1 else 0].increment()

    /**
     * Registers the gauges of [source]: what it holds by kind and state, its largest lag, the documents past the lag
     * thresholds, and the age of the summary they read. They hold [source] strongly: a gauge never reads 0 because its
     * source was collected.
     */
    fun bind(source: SnapshotSource<*, *>) {
        for (kind in KINDS) {
            for (state in SnapshotSummary.State.entries) {
                gauge(INSTALLED, source, KIND, tag(kind), STATE, tag(state)) {
                    it.summary().of(kind).held(state).toDouble()
                }
            }
            gauge(LAG, source, KIND, tag(kind), unit = SECONDS) { seconds(it.summary().of(kind).maxLag) }
            gauge(LAGGING, source, KIND, tag(kind), LEVEL, WARN) { it.summary().of(kind).lagging.toDouble() }
            gauge(LAGGING, source, KIND, tag(kind), LEVEL, ESCALATE) { it.summary().of(kind).escalated.toDouble() }
        }
        gauge(SUMMARY_AGE, source, unit = SECONDS) { summaryAge(it.summary()) }
    }

    /** Seconds since the pass that produced [summary]; NaN before the first pass. */
    private fun summaryAge(summary: SnapshotSummary): Double =
        summary.takenAt?.let { seconds(Duration.between(it, clock.instant())) } ?: Double.NaN

    private fun seconds(duration: Duration): Double = duration.toNanos() / NANOS_PER_SECOND

    private fun gauge(
        name: String,
        source: SnapshotSource<*, *>,
        vararg tags: String,
        unit: String? = null,
        value: (SnapshotSource<*, *>) -> Double,
    ) {
        Gauge.builder(name, source) { value(it) }.tags(*tags).baseUnit(unit).strongReference(true).register(registry)
    }

    @InternalCoSecApi
    companion object {
        const val LOADS = "cosec.snapshot.loads"
        const val COMPILE_DURATION = "cosec.snapshot.compile.duration"
        const val INSTALLS = "cosec.snapshot.installs"
        const val INSTALLED = "cosec.snapshot.installed"
        const val LAG = "cosec.snapshot.lag"
        const val LAGGING = "cosec.snapshot.lagging"
        const val LAG_EXCEEDED = "cosec.snapshot.lag.exceeded"
        const val REGRESSIONS = "cosec.snapshot.regressions"
        const val LOST = "cosec.snapshot.lost"
        const val EVICTIONS = "cosec.snapshot.evictions"
        const val HANDOFFS_REFUSED = "cosec.snapshot.handoffs.refused"
        const val SUMMARY_AGE = "cosec.snapshot.summary.age"

        private const val KIND = "kind"
        private const val RESULT = "result"
        private const val STATE = "state"
        private const val LEVEL = "level"
        private const val WARN = "warn"
        private const val ESCALATE = "escalate"
        private const val SECONDS = "seconds"
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        private val KINDS = SnapshotKind.entries

        /** The outcomes of a load that ran: every one but a hit. */
        private val LOADED = LoadOutcome.entries - LoadOutcome.HIT

        private fun tag(value: Enum<*>): String = value.name.lowercase()
    }
}
