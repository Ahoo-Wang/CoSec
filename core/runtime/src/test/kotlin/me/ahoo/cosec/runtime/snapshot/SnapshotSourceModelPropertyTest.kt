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
 * correctly ([SourceModel]). Each seed is one [SourceModelScript], run on [SourceModelThreads] alone: no real thread, so a
 * seed replays exactly.
 *
 * The seeds run over the [KeyRunner] (RFC 0013 B2b) at its default K and at K = 0, with every check of the model.
 *
 * A failing seed is shrunk (events are left out while the same check still fails) and reported with its event log and
 * the command that runs it alone: `-PpropertySeed=<seed>`.
 */
class SnapshotSourceModelPropertyTest {
    /** The kinds of [KeyEvent] the runner took in the seed now running (every run is on this thread alone). */
    private val taken = sortedSetOf<String>()

    /** The runner at its default K ([KeyRunner.K], 4). */
    @Test
    fun theKeyRunnerKeepsToTheModelInEveryScriptedOrder() = quietly {
        runSeeds("KeyRunner model", "theKeyRunnerKeepsToTheModelInEveryScriptedOrder", counting(), EVENT_KINDS)
    }

    /**
     * K = 0: every caller's drain takes one caller event and hands the rest off, so the hand-off and the load lane carry
     * as much as they ever do. A load's end left behind a spent budget, or a hand-off close leaves, fails here.
     */
    @Test
    fun theKeyRunnerKeepsToTheModelWhenEveryDrainHandsOff() = quietly {
        runSeeds(
            "KeyRunner model, K = 0",
            "theKeyRunnerKeepsToTheModelWhenEveryDrainHandsOff",
            counting(k = 0),
            EVENT_KINDS
        )
    }

    /**
     * Seed 9191 at K = 0, a fixed case (stress run 44163444213): an eviction queued behind a hand-off is claimed while
     * the family holds two absent entries, then, in the same event, an install moves the other one to the documents
     * (a compilation that calls `recompile`). Judged after the event, the eviction looked like one within the bound.
     */
    @Test
    fun anEvictionIsJudgedAtItsClaimNotAfterItsEvent() = quietly { fixedCase(SEED_9191) }

    /**
     * Seed 872 at K = 0, a fixed case: a claim taken when its kind is back at its bound, which the claim refuses. The
     * first seed of 10,000 at which the model kills a claim that ignores the bound (`snp-7-claim-ignores-the-bound`).
     */
    @Test
    fun aClaimAtItsKindsBoundIsRefused() = quietly { fixedCase(SEED_872) }

    private fun fixedCase(seed: Int) {
        val run = SourceModelRun(SourceModelScript.generate(seed, EVENTS), ModelRunners.runner(k = 0)).run()
        run.failure.assert().describedAs("seed $seed").isNull()
        run.counts.evictionsAtClaim.assert().isGreaterThan(0)
    }

    private fun counting(k: Int = KeyRunner.K) =
        ModelRunners.runner(k) { taken += "took ${it::class.simpleName}" }

    private fun quietly(block: () -> Unit) {
        // Every load that fails logs with its stack trace: at the TRACE level tests run with, tens of megabytes.
        val logger = LoggerFactory.getLogger(SnapshotSourceModelPropertyTest::class.java.packageName) as Logger
        val level = logger.level
        logger.level = Level.OFF
        try {
            block()
        } finally {
            logger.level = level
        }
    }

    private fun runSeeds(name: String, test: String, runners: ModelRunners, kinds: List<String> = emptyList()) {
        val only = System.getProperty("cosec.property.seed")?.toInt()
        val seeds = if (only != null) listOf(only) else (0 until seedCount()).toList()
        val reached = sortedMapOf<String, Int>()
        val totals = SourceModel.Counts()
        for (seed in seeds) {
            taken.clear()
            val run = SourceModelRun(SourceModelScript.generate(seed, EVENTS), runners).run()
            run.failure?.let { throw AssertionError(report(seed, it, test, runners)) }
            run.reached.forEach { reached.merge(it, 1, Int::plus) }
            taken.forEach { reached.merge(it, 1, Int::plus) }
            add(run.counts, totals, reached)
        }
        println(
            "$name: ${seeds.size} seeds; seeds reaching each kind of event: $reached; checked " +
                "${totals.installs} installs, ${totals.answered} answered waiters, ${totals.failures} compile failures, " +
                "${totals.readTimeouts} store read timeouts, ${totals.coldTimeouts} request timeouts, " +
                "${totals.evictions} evictions (${totals.evictionsAtClaim} judged at the claim), " +
                "${totals.regressions} regressions, ${totals.resetsChecked} reset reloads, " +
                "${totals.lostWritesChecked} lost invalidations ${totals.lostWritesByLag}, " +
                "${totals.passReadsChecked} documents of passes read after their call (${totals.passReadsOwed} owed)"
        )
        if (only == null) {
            // A property test that rarely gets past the first load proves little: each kind of event is reached.
            (VACUITY + kinds).forEach { kind ->
                (reached[kind] ?: 0).assert().describedAs(
                    "seeds reaching '$kind'"
                ).isGreaterThan(0)
            }
        }
    }

    /** Adds one run's [counts] to [totals], and to [reached] the kinds of check it got to. */
    private fun add(counts: SourceModel.Counts, totals: SourceModel.Counts, reached: MutableMap<String, Int>) {
        totals.installs += counts.installs
        totals.failures += counts.failures
        totals.answered += counts.answered
        totals.readTimeouts += counts.readTimeouts
        totals.coldTimeouts += counts.coldTimeouts
        totals.evictions += counts.evictions
        totals.evictionsAtClaim += counts.evictionsAtClaim
        totals.regressions += counts.regressions
        totals.resetsChecked += counts.resetsChecked
        totals.lostWritesChecked += counts.lostWritesChecked
        totals.passReadsChecked += counts.passReadsChecked
        totals.passReadsOwed += counts.passReadsOwed
        counts.lostWritesByLag.forEach { (lag, n) -> totals.lostWritesByLag.merge(lag, n, Int::plus) }
        mapOf(
            "reset reloads checked" to counts.resetsChecked,
            "lost invalidation installed by a pass" to counts.lostWritesChecked,
            "lost invalidation installed past a load in flight" to (counts.lostWritesByLag[SourceModelScript.Lag.IN_FLIGHT] ?: 0),
            "pass read checked" to counts.passReadsChecked,
            "pass read owed past a stuck compilation" to counts.passReadsOwed,
            "store read timed out" to counts.readTimeouts,
            "request answered by its timeout" to counts.coldTimeouts,
            "eviction" to counts.evictions,
            CLAIM_JUDGED to counts.evictionsAtClaim,
            "regression installed" to counts.regressions,
            "compile failed" to counts.failures,
        ).filterValues { it > 0 }.keys.forEach { reached.merge(it, 1, Int::plus) }
    }

    private fun seedCount(): Int = maxOf(SEEDS, Integer.getInteger("cosec.property.iterations", 0) / 10)

    /** The failure of [seed], shrunk: the shortest script found that fails the same check, with its events. */
    private fun report(seed: Int, failure: String, test: String, runners: ModelRunners): String {
        val script = shrink(SourceModelScript.generate(seed, EVENTS), shapeOf(failure), runners)
        val run = SourceModelRun(script, runners).run()
        return buildString {
            appendLine("Seed $seed: ${run.failure ?: failure}")
            appendLine(
                "Shrunk to ${script.events.size} events (store holds ${script.initial}, subscribe: ${script.duringSubscribe}):"
            )
            run.log.forEach { appendLine("  $it") }
            append(
                "Replay: ./gradlew :runtime:test --tests '${SnapshotSourceModelPropertyTest::class.qualifiedName}.$test' -PpropertySeed=$seed"
            )
        }
    }

    /**
     * Leaves events out, one at a time from the end, while the run still fails with the same kind of violation; again
     * until a whole pass leaves nothing out.
     */
    private fun shrink(script: SourceModelScript, shape: String, runners: ModelRunners): SourceModelScript {
        val failedAt = SourceModelRun(script, runners).run().log.size
        var current = script.copy(events = script.events.take(maxOf(0, failedAt - 1)))
        do {
            val before = current.events.size
            for (index in current.events.indices.reversed()) {
                if (index > current.events.lastIndex) continue
                val candidate = current.copy(events = current.events.filterIndexed { i, _ -> i != index })
                val failure = SourceModelRun(candidate, runners).run().failure
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
        const val SEED_9191 = 9191
        const val SEED_872 = 872

        /** Reached by every run over the runner: an eviction judged at its claim ([SourceModel.claimed]). */
        const val CLAIM_JUDGED = "eviction judged at its claim"
        val DIGITS = Regex("[0-9]+")
        val LISTS = Regex("\\[[^]]*]")
        val VACUITY = listOf(
            "store read timed out", "request answered by its timeout", "eviction", "regression installed", "compile failed",
            "listener called in subscribe", "close mid-load", "recompile", "store regressed", "converged",
            "store read failed", "compile rejected", "abandoned read answered",
            "reset reloads checked", "lost invalidation installed by a pass",
            "lost invalidation installed past a load in flight", "pass read checked",
        )

        /**
         * Over the runner, each kind of [KeyEvent] is taken in some seed, but `EffectFailed`: no effect throws in the
         * model, whose meters never do. `KeyRunnerTest`'s effects that throw queue it, and the drain takes it.
         */
        val EVENT_KINDS = listOf(
            "Requested", "Changed", "Recompile", "ReadDone", "ReadFailed", "ReadTimedOut", "Compiled", "CompileCrashed",
            "CompileRejected", "RetryDue", "Evict", "Close",
        ).map { "took $it" } + CLAIM_JUDGED
    }
}
