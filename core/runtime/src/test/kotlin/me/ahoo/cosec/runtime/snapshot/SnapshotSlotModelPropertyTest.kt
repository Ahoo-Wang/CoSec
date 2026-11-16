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

package me.ahoo.cosec.runtime.snapshot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * The snapshot source against a reference model, over scripted orders of events (RFC 0013 B2b gate 1): installs never
 * go back, a failure installs nothing, at most one live read per key, and every waiter is answered, and answered
 * correctly ([SlotModel]). Each seed is one [SlotModelScript], run on [SlotModelThreads] alone: no real thread, so a
 * seed replays exactly.
 *
 * A failing seed is shrunk (events are left out while the same check still fails) and reported with its event log and
 * the command that runs it alone: `-PpropertySeed=<seed>`.
 */
class SnapshotSlotModelPropertyTest {
    @Test
    fun theSourceKeepsToTheModelInEveryScriptedOrder() {
        // Every load that fails logs with its stack trace: at the TRACE level tests run with, tens of megabytes.
        val logger = LoggerFactory.getLogger(SnapshotSlotModelPropertyTest::class.java.packageName) as Logger
        val level = logger.level
        logger.level = Level.OFF
        try {
            runSeeds()
        } finally {
            logger.level = level
        }
    }

    private fun runSeeds() {
        val only = System.getProperty("cosec.property.seed")?.toInt()
        val seeds = if (only != null) listOf(only) else (0 until seedCount()).toList()
        val reached = sortedMapOf<String, Int>()
        val totals = SlotModel.Counts()
        for (seed in seeds) {
            val run = SlotModelRun(SlotModelScript.generate(seed, EVENTS)).run()
            run.failure?.let { throw AssertionError(report(seed, it)) }
            run.reached.forEach { reached.merge(it, 1, Int::plus) }
            add(run.counts, totals, reached)
        }
        println(
            "Slot model: ${seeds.size} seeds; seeds reaching each kind of event: $reached; checked " +
                "${totals.installs} installs, ${totals.answered} answered waiters, ${totals.failures} compile failures, " +
                "${totals.readTimeouts} store read timeouts, ${totals.coldTimeouts} request timeouts, " +
                "${totals.evictions} evictions, ${totals.regressions} regressions, ${totals.resetsChecked} reset reloads, " +
                "${totals.lostWritesChecked} lost invalidations"
        )
        if (only == null) {
            // A property test that rarely gets past the first load proves little: each kind of event is reached.
            VACUITY.forEach { kind ->
                (reached[kind] ?: 0).assert().describedAs(
                    "seeds reaching '$kind'"
                ).isGreaterThan(0)
            }
        }
    }

    /** Adds one run's [counts] to [totals], and to [reached] the kinds of check it got to. */
    private fun add(counts: SlotModel.Counts, totals: SlotModel.Counts, reached: MutableMap<String, Int>) {
        totals.installs += counts.installs
        totals.failures += counts.failures
        totals.answered += counts.answered
        totals.readTimeouts += counts.readTimeouts
        totals.coldTimeouts += counts.coldTimeouts
        totals.evictions += counts.evictions
        totals.regressions += counts.regressions
        totals.resetsChecked += counts.resetsChecked
        totals.lostWritesChecked += counts.lostWritesChecked
        mapOf(
            "reset reloads checked" to counts.resetsChecked,
            "lost invalidation installed by a pass" to counts.lostWritesChecked,
            "store read timed out" to counts.readTimeouts,
            "request answered by its timeout" to counts.coldTimeouts,
            "eviction" to counts.evictions,
            "regression installed" to counts.regressions,
            "compile failed" to counts.failures,
        ).filterValues { it > 0 }.keys.forEach { reached.merge(it, 1, Int::plus) }
    }

    private fun seedCount(): Int = maxOf(SEEDS, Integer.getInteger("cosec.property.iterations", 0) / 10)

    /** The failure of [seed], shrunk: the shortest script found that fails the same check, with its events. */
    private fun report(seed: Int, failure: String): String {
        val script = shrink(SlotModelScript.generate(seed, EVENTS), shapeOf(failure))
        val run = SlotModelRun(script).run()
        return buildString {
            appendLine("Seed $seed: ${run.failure ?: failure}")
            appendLine(
                "Shrunk to ${script.events.size} events (store holds ${script.initial}, subscribe: ${script.duringSubscribe}):"
            )
            run.log.forEach { appendLine("  $it") }
            append(
                "Replay: ./gradlew :runtime:test --tests '${SnapshotSlotModelPropertyTest::class.qualifiedName}' -PpropertySeed=$seed"
            )
        }
    }

    /**
     * Leaves events out, one at a time from the end, while the run still fails with the same kind of violation; again
     * until a whole pass leaves nothing out.
     */
    private fun shrink(script: SlotModelScript, shape: String): SlotModelScript {
        val failedAt = SlotModelRun(script).run().log.size
        var current = script.copy(events = script.events.take(maxOf(0, failedAt - 1)))
        do {
            val before = current.events.size
            for (index in current.events.indices.reversed()) {
                if (index > current.events.lastIndex) continue
                val candidate = current.copy(events = current.events.filterIndexed { i, _ -> i != index })
                val failure = SlotModelRun(candidate).run().failure
                if (failure != null && shapeOf(failure) == shape) current = candidate
            }
        } while (current.events.size < before)
        return current
    }

    /** A violation without its numbers and event labels: what must stay the same while shrinking. */
    private fun shapeOf(failure: String): String =
        failure.substringBefore(" (after ").replace(LISTS, "[]").replace(DIGITS, "#")

    private companion object {
        const val SEEDS = 500
        const val EVENTS = 250
        val DIGITS = Regex("[0-9]+")
        val LISTS = Regex("\\[[^]]*]")
        val VACUITY = listOf(
            "store read timed out", "request answered by its timeout", "eviction", "regression installed", "compile failed",
            "listener called in subscribe", "close mid-load", "recompile", "store regressed", "converged",
            "store read failed", "compile rejected", "abandoned read answered",
            "reset reloads checked", "lost invalidation installed by a pass",
        )
    }
}
