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

package me.ahoo.cosec.system

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * The leg of the previous release (`previous=<tag>` of system-test.yml, RFC 0014 T): the gateway and control-plane
 * images of that release in front of the services of this commit. Every scenario of the control-host leg runs there,
 * except what holds only from a later release than the one under test: listed here, in one place, each with the release
 * it holds from and why. A listed scenario is reported as left out with that reason, never skipped silently, and runs
 * again once `previous` reaches its release. 8.0.0-beta.9 is the first release with RFC 0014's readers, and the first
 * the leg runs against whole.
 */
object PreviousRelease {
    /** The release whose images the stack runs, or null outside that leg. */
    val underTest: String? get() = System.getProperty("cosec.system.previous")?.takeIf { it.isNotBlank() }

    /** Why a scenario holds only from [since]: what the earlier release lacks. */
    class Later(val since: String, val reason: String)

    /**
     * Scenarios (`Class` or `Class#method`) that hold only from a later release than an earlier one under test, none so
     * far: against 8.0.0-beta.9, the first release with RFC 0014's readers, every scenario of the leg runs (run
     * 45570821364). A scenario that a later release changes and the previous one cannot meet is listed here, with the
     * release it holds from and why, until `previous` reaches it.
     */
    val LATER: Map<String, Later> = emptyMap()

    /** What [scenario] needs of the release under test, if anything, or null when it runs. */
    fun leftOut(scenario: String, underTest: String? = this.underTest, listed: Map<String, Later> = LATER): String? {
        val release = underTest ?: return null
        val later = listed[scenario] ?: listed[scenario.substringBefore('#')] ?: return null
        return if (Release.of(release) < Release.of(later.since)) {
            "holds from ${later.since}, not against $release: ${later.reason}"
        } else {
            null
        }
    }

    /** A release's place in the order: 8.0.0-beta.9 < 8.0.0-rc.1 < 8.0.0 < 8.0.1 < 8.1.0. */
    class Release private constructor(private val parts: List<Int>) : Comparable<Release> {
        override fun compareTo(other: Release): Int =
            parts.zip(other.parts).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) } ?: 0

        companion object {
            private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(beta|rc|RC)\.?(\d+))?""")
            private const val STAGE = 4
            private const val COUNT = 5
            private const val FINAL = 3

            fun of(text: String): Release {
                val groups = requireNotNull(PATTERN.matchEntire(text)) { "[$text] is not a release" }.groupValues
                val stage = when (groups[STAGE].lowercase()) {
                    "beta" -> 1
                    "rc" -> 2
                    else -> FINAL
                }
                return Release((1..3).map { groups[it].toInt() } + stage + (groups[COUNT].toIntOrNull() ?: 0))
            }
        }
    }

    /** Leaves out, with its reason, a scenario [LATER] lists for the release under test. */
    class Condition : ExecutionCondition {
        override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
            val type = context.testClass.orElse(null) ?: return ConditionEvaluationResult.enabled("not a scenario")
            val scenario = type.simpleName + context.testMethod.map { "#${it.name}" }.orElse("")
            return leftOut(scenario)?.let(ConditionEvaluationResult::disabled)
                ?: ConditionEvaluationResult.enabled("runs against ${underTest ?: "this commit"}")
        }
    }
}
