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

package me.ahoo.cosec.compat

/**
 * Which release the previous side is (`cosec-previous`), and what holds only from a release on (RFC 0014 T). A case
 * that needs a reader of a later release than the baseline is not skipped: it is run, and must still fail, so the PR
 * that moves the baseline is told which cases now hold and must turn them on.
 */
object Baseline {
    val previous: String = checkNotNull(System.getProperty("cosec.compat.previous.version")) {
        "cosec.compat.previous.version is not set: run the tests through Gradle."
    }

    /** Whether the previous side is [release] or later. */
    fun reached(release: String): Boolean = Release.of(previous) >= Release.of(release)

    /**
     * [check], which holds against a previous release of [release] or later: run, and required to hold, once the
     * baseline is there; before that, run and required to fail, the gap the baseline still has.
     */
    fun from(release: String, what: String, check: () -> Unit) {
        if (reached(release)) {
            check()
            return
        }
        val holds = try {
            check()
            true
        } catch (_: AssertionError) {
            false
        }
        if (holds) {
            throw AssertionError("$what already holds against $previous: it holds from $previous, not $release; move it.")
        }
    }

    /** A release's place in the order: 8.0.0-beta.9 < 8.0.0-rc.1 < 8.0.0 < 8.0.1 < 8.1.0. */
    class Release private constructor(private val numbers: List<Int>, private val stage: Int, private val count: Int) :
        Comparable<Release> {
        override fun compareTo(other: Release): Int =
            compareValuesBy(this, other, { it.numbers[0] }, { it.numbers[1] }, { it.numbers[2] }, { it.stage }, { it.count })

        companion object {
            private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(beta|rc|RC)\.?(\d+))?""")

            fun of(text: String): Release {
                val match = requireNotNull(PATTERN.matchEntire(text)) { "[$text] is not a release" }
                val (major, minor, patch, stage, count) = match.destructured
                val rank = when (stage.lowercase()) {
                    "beta" -> 1
                    "rc" -> 2
                    else -> 3
                }
                return Release(listOf(major.toInt(), minor.toInt(), patch.toInt()), rank, count.toIntOrNull() ?: 0)
            }
        }
    }
}
