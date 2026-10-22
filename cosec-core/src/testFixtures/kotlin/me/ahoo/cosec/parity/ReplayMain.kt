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

/** Every sample agreed and the gate is met. */
const val REPLAY_OK = 0

/** At least one sample is a mismatch or a candidate error. */
const val REPLAY_DIFFERENCES = 1

/** No differences, but the replay does not meet its [ReplayGate]: it is not gate evidence. */
const val REPLAY_GATE_NOT_MET = 2

/** The arguments are wrong. */
const val REPLAY_USAGE = 64

private const val USAGE =
    "Usage: DecisionReplay <export directory> [--min-samples=N] [--min-days=D] [--max-both-failed-rate=R]"

/**
 * The command line of [DecisionReplay]: `<export directory>` and optional [ReplayGate] thresholds
 * (`--min-samples=1000000`, `--min-days=7`, `--max-both-failed-rate=0.01` by default; the Gradle task passes
 * `-PreplayMinSamples`, `-PreplayMinDays` and `-PreplayMaxBothFailedRate`).
 *
 * [baseline] and [candidate] default to the two implementations RFC 0002 compares. Prints the summary and returns
 * [REPLAY_OK], [REPLAY_DIFFERENCES] (which wins when the gate is not met either), [REPLAY_GATE_NOT_MET] or
 * [REPLAY_USAGE].
 */
fun replayMain(
    args: Array<String>,
    out: PrintStream = System.out,
    baseline: AuthorizationFactory = AuthorizationFactory.LEGACY,
    candidate: AuthorizationFactory = AuthorizationFactory.ENGINE
): Int {
    val directory = args.firstOrNull()?.takeUnless { it.startsWith("--") }
    val gate = directory?.let { gateOf(args.drop(1)) }
    if (directory == null || gate == null) {
        out.println(USAGE)
        return REPLAY_USAGE
    }
    val path = Path.of(directory).toAbsolutePath()
    val report = DecisionReplay(baseline, candidate, gate).run(path)
    out.print(report.summary())
    out.println("Report: ${path.resolve(DecisionReplay.REPORT_FILE)}")
    if (report.differences > 0) {
        out.println("DIFFERENCES: ${path.resolve(DecisionReplay.MISMATCHES_FILE)}")
        return REPLAY_DIFFERENCES
    }
    if (report.gateUnmet.isNotEmpty()) {
        return REPLAY_GATE_NOT_MET
    }
    return REPLAY_OK
}

/** Null when an option is unknown or malformed. */
private fun gateOf(options: List<String>): ReplayGate? {
    var gate = ReplayGate()
    options.forEach { option ->
        val value = option.substringAfter('=', "")
        gate = when (option.substringBefore('=')) {
            "--min-samples" -> gate.copy(minSamples = value.toLongOrNull() ?: return null)
            "--min-days" -> gate.copy(minDays = value.toDoubleOrNull() ?: return null)
            "--max-both-failed-rate" -> gate.copy(maxBothFailedRate = value.toDoubleOrNull() ?: return null)
            else -> return null
        }
    }
    return gate
}

/**
 * `./gradlew :cosec-core:engineReplay -PreplayDir=<dir>`: see [replayMain]. Exits with its code, so the Gradle task
 * fails unless the replay is clean and meets the gate.
 */
fun main(args: Array<String>) {
    val code = replayMain(args)
    if (code != REPLAY_OK) {
        exitProcess(code)
    }
}
