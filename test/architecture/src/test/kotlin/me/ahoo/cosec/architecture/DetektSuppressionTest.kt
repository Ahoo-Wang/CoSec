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

package me.ahoo.cosec.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0013 §8.1: the structure rules of detekt (`config/detekt/detekt.yml`) are suppressed only at a declaration, with
 * the batch that removes the finding or the reason it stays on the line above the `@Suppress`, and their number only
 * goes down: [RECORDED] is what main code suppresses today, and a change of it is part of the diff a reviewer reads.
 */
class DetektSuppressionTest {
    companion object {
        /** The suppressions of each structure rule in main code. Lower a number when a batch removes one. */
        val RECORDED: Map<String, Int> = mapOf(
            "TooManyFunctions" to 17,
            "NestedBlockDepth" to 9,
            "LongParameterList" to 6,
        )

        private val SUPPRESS = Regex("""@(?:file:)?Suppress\(([^)]*)\)""")

        /** Every suppression of a structure rule in main code. */
        fun suppressions(): List<Suppression> = MainSources.files.flatMap { source ->
            source.lines.mapIndexedNotNull { index, line ->
                val rules = SUPPRESS.find(line)?.groupValues?.get(1)?.let { arguments ->
                    Regex("\"(\\w+)\"").findAll(arguments).map { it.groupValues[1] }.toSet() intersect RECORDED.keys
                }.orEmpty()
                rules.takeIf { it.isNotEmpty() }?.let {
                    Suppression(source.path, index + 1, it, source.lines.getOrNull(index - 1))
                }
            }
        }
        private val REASON = Regex("""^\s*// (?:(?:${Batch.entries.joinToString("|")}): |stays: )\S""")
    }

    /** A suppression of a structure rule, at [line] of [path], with the line above it. */
    class Suppression(val path: String, val line: Int, val rules: Set<String>, val above: String?) {
        val reasoned: Boolean get() = above != null && REASON.containsMatchIn(above)
    }

    private val suppressions: List<Suppression> = suppressions()

    @Test
    fun `a structure rule is suppressed with its batch or the reason it stays`() {
        suppressions.filterNot { it.reasoned }.map { "${it.path}:${it.line}: ${it.rules.joinToString()}" }.assert()
            .describedAs(
                "@Suppress of a structure rule without a `// B<n>: …` or `// stays: …` line above it: fix the " +
                    "finding, or name the batch that removes it or the reason it stays, and have it reviewed",
            ).isEmpty()
    }

    @Test
    fun `the number of suppressions is the recorded one`() {
        val counted = RECORDED.keys.associateWith { rule -> suppressions.count { rule in it.rules } }
        counted.assert().describedAs(
            "suppressions of detekt's structure rules in main code, against DetektSuppressionTest.RECORDED: above it, " +
                "split the code instead; below it, lower the recorded number so the shrink stays",
        ).isEqualTo(RECORDED)
    }
}
