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

package me.ahoo.cosec.parity

import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

/** No differences, and the gate is met with RFC 0002 §8.3's thresholds: the replay is gate evidence. */
const val REPLAY_OK = 0

/** At least one sample is a mismatch, a candidate error, or a failure alike for a different cause. */
const val REPLAY_DIFFERENCES = 1

/** No differences, but the gate is not met, or met only with lenient thresholds: not gate evidence. */
const val REPLAY_GATE_NOT_MET = 2

/** The arguments are wrong. */
const val REPLAY_USAGE = 64

/** The export cannot be replayed: a malformed file or line. Nothing was judged. */
const val REPLAY_INVALID_INPUT = 70

private const val USAGE = "Usage: DecisionReplay <export directory> [--min-samples=N] [--min-days=D] " +
    "[--min-distinct-days=N] [--max-both-failed-rate=R] [--min-agreement-rate=R]"

/**
 * The command line of [DecisionReplay]: `<export directory>` and optional [ReplayGate] thresholds, RFC 0002 §8.3's by
 * default (`--min-samples=1000000`, `--min-days=7`, `--min-distinct-days=7`, `--max-both-failed-rate=0.01`,
 * `--min-agreement-rate=0.9`; the Gradle task passes `-PreplayMinSamples`, `-PreplayMinDays`, `-PreplayMinDistinctDays`,
 * `-PreplayMaxBothFailedRate` and `-PreplayMinAgreementRate`). Weaker thresholds are for trying the tool out: a replay
 * judged by them exits with [REPLAY_GATE_NOT_MET] even when it meets them.
 *
 * Any report left in the directory is deleted first, even when the arguments turn out to be wrong.
 * [baseline] and [candidate] default to the two implementations RFC 0002 compares. Prints the summary and returns
 * [REPLAY_OK], [REPLAY_DIFFERENCES] (which wins over an unmet gate), [REPLAY_GATE_NOT_MET], [REPLAY_USAGE] or
 * [REPLAY_INVALID_INPUT].
 */
fun replayMain(
    args: Array<String>,
    out: PrintStream = System.out,
    baseline: AuthorizationFactory = AuthorizationFactory.LEGACY,
    candidate: AuthorizationFactory = AuthorizationFactory.ENGINE
): Int {
    val path = args.firstOrNull()?.takeUnless { it.startsWith("--") }?.let { Path.of(it).toAbsolutePath() }
    path?.let(DecisionReplay::deleteOutputs)
    val gate = path?.let { gateOf(args.drop(1)) }
    if (path == null || gate == null) {
        out.println(USAGE)
        return REPLAY_USAGE
    }
    val report = try {
        DecisionReplay(baseline, candidate, gate).run(path)
    } catch (cause: IllegalArgumentException) {
        out.println("INVALID INPUT: ${cause.message}${cause.cause?.let { " (${it.message})" }.orEmpty()}")
        return REPLAY_INVALID_INPUT
    }
    out.print(report.summary())
    out.println("Report: ${path.resolve(DecisionReplay.REPORT_FILE)}")
    if (report.differences > 0) {
        out.println("Differences: ${path.resolve(DecisionReplay.MISMATCHES_FILE)}")
    }
    return exitCodeOf(report)
}

/** [REPLAY_DIFFERENCES] wins over an unmet gate; only evidence is [REPLAY_OK]. */
fun exitCodeOf(report: ReplayReport): Int = when {
    report.differences > 0 -> REPLAY_DIFFERENCES
    !report.evidence -> REPLAY_GATE_NOT_MET
    else -> REPLAY_OK
}

/** Null when an option is unknown or malformed, or a threshold out of range. */
private fun gateOf(options: List<String>): ReplayGate? {
    var gate = ReplayGate()
    options.forEach { option ->
        val value = option.substringAfter('=', "")
        gate = try {
            when (option.substringBefore('=')) {
                "--min-samples" -> gate.copy(minSamples = value.toLongOrNull() ?: return null)
                "--min-days" -> gate.copy(minDays = value.toDoubleOrNull() ?: return null)
                "--min-distinct-days" -> gate.copy(minDistinctDays = value.toIntOrNull() ?: return null)
                "--max-both-failed-rate" -> gate.copy(maxBothFailedRate = value.toDoubleOrNull() ?: return null)
                "--min-agreement-rate" -> gate.copy(minRecordedAgreementRate = value.toDoubleOrNull() ?: return null)
                else -> return null
            }
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    return gate
}

/**
 * `./gradlew :cosec-core:engineReplay -PreplayDir=<dir>`: see [replayMain]. Exits with its code, so the Gradle task
 * fails unless the replay is gate evidence.
 */
fun main(args: Array<String>) {
    val code = replayMain(args)
    if (code != REPLAY_OK) {
        exitProcess(code)
    }
}
