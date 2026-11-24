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

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.pow

/**
 * The matcher's worst case stays linear in a segment's length (#1853, review M2 of #1200). The segment bound (`PIP-5`,
 * 256 characters as the pattern reads it) keeps a request's matching at the cost `SegmentBoundBenchmark` measures only
 * while matching a fixed glob costs its units times the value's length: a matcher that went quadratic (the
 * backtracking review F4 of #1040 replaced took 0.2–0.4 s on one 8 KB segment) would make every segment cost more than
 * the bound was chosen for, and a longer one 256 times as much at 16 times the length.
 *
 * Each glob is a worst case of `GlobBoundBenchmark` or `SegmentBoundBenchmark`, matched with the evaluation's table and
 * without it (the two-pointer match), at [SHORT] and [LONG] characters. The matcher has no step a test can count
 * without a hook on the hot path, so the test compares times, made to need no quiet machine:
 *
 * - The lengths are 16 times apart, so linear and quadratic are far apart: for a glob whose literal the value must also
 *   hold, the work is in the characters past it, `r` = (long − literal) / (short − literal), 16 for the short globs and
 *   about 25.6 for the 100-character literal; quadratic is `r`². The bound is their geometric mean, `r`^1.5 (64 and
 *   about 130), so either side has a margin of `r`^0.5 (4 and 5) against a noisy measurement, where 8 times apart with a
 *   bound of 32 had 2.7 for the long literal, and once ran out of it on a shared runner (32.04, run 45317697188).
 * - The short and long batches are timed alternately, [BATCHES] pairs after a warm-up of both, and each length keeps
 *   its best: a pause of the scheduler lands in one batch and is dropped, and never raises one length for the whole
 *   measurement while the other was measured at another time.
 */
class SegmentGlobCostTest {
    @ParameterizedTest
    @ValueSource(strings = ["literal", "long-literal", "alternating", "captures"])
    fun `matching the worst globs grows linearly with the segment`(shape: String) {
        val pattern = HttpPathPattern.compile(PATTERNS.getValue(shape), caseSensitive = false)
        val short = HttpPath.parse(value(shape, SHORT))
        val long = HttpPath.parse(value(shape, LONG))
        val literal = LITERALS.getValue(shape)
        val linear = (LONG - literal).toDouble() / (SHORT - literal)
        val bound = linear.pow(1.5)
        for (table in listOf(true, false)) {
            val scratch = if (table) EvaluationScratch() else null
            val match: (HttpPath) -> Unit = { pattern.match(it, scratch) }
            repeat(WARMUP) {
                match(short)
                match(long)
            }
            var shortest = Long.MAX_VALUE
            var longest = Long.MAX_VALUE
            repeat(BATCHES) {
                shortest = minOf(shortest, timed { repeat(REPEATS) { match(short) } })
                longest = minOf(longest, timed { repeat(REPEATS) { match(long) } })
            }
            val ratio = longest.toDouble() / shortest
            val measured = "$shape, table=$table: $LONG characters cost $ratio times $SHORT (linear $linear, bound $bound)"
            println("SegmentGlobCostTest: $measured")
            ratio.assert().describedAs(measured).isLessThan(bound)
        }
    }

    private fun timed(batch: () -> Unit): Long {
        val start = System.nanoTime()
        batch()
        return System.nanoTime() - start
    }

    private fun value(shape: String, length: Int): String = when (shape) {
        "alternating" -> "/" + "a".repeat(length - 1) + "b"
        "captures" -> "/x" + "a".repeat(length - 1)
        else -> "/" + "a".repeat(length)
    }

    private companion object {
        const val SHORT = 256
        const val LONG = 4096
        const val WARMUP = 500
        const val BATCHES = 15
        const val REPEATS = 50

        val PATTERNS = mapOf(
            "literal" to "/*ab*",
            "long-literal" to "/*" + "a".repeat(100) + "b*",
            "alternating" to "/{v}a*a*a*a*a*a*a*b",
            "captures" to "/*{a}*{c}*{d}*{e}*{f}*{g}*{h}x{v}",
        )

        /** The characters of each glob's literal the value must hold too: the work is in the characters past it. */
        val LITERALS = mapOf("literal" to 0, "long-literal" to 100, "alternating" to 0, "captures" to 0)
    }
}
