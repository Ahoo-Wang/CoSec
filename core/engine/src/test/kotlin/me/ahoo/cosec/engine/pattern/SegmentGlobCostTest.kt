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

/**
 * The matcher's worst case stays linear in a segment's length (#1853, review M2 of #1200). The segment bound (`PIP-5`,
 * 256 characters as the pattern reads it) keeps a request's matching at the cost `SegmentBoundBenchmark` measures only
 * while matching a fixed glob costs its units times the value's length: a matcher that went quadratic (the
 * backtracking review F4 of #1040 replaced took 0.2–0.4 s on one 8 KB segment) would make every segment cost more than
 * the bound was chosen for, and a longer one, as a 2,048-character path is, 64 times as much as one of 256.
 *
 * Each glob is a worst case of `GlobBoundBenchmark` or `SegmentBoundBenchmark`, matched with the evaluation's table and
 * without it (the two-pointer match). The ratio of the best of [BATCHES] timed batches at 2,048 and at 256 characters
 * must stay under [MAX_RATIO]: linear is 8 (the long literal, which skips its own length, about 12), quadratic 64.
 * Measured: 5 to 8, and 12 for the long literal (#1860).
 */
class SegmentGlobCostTest {
    @ParameterizedTest
    @ValueSource(strings = ["literal", "long-literal", "alternating", "captures"])
    fun `matching the worst globs grows linearly with the segment`(shape: String) {
        val pattern = HttpPathPattern.compile(PATTERNS.getValue(shape), caseSensitive = false)
        val short = HttpPath.parse(value(shape, SHORT))
        val long = HttpPath.parse(value(shape, LONG))
        for (table in listOf(true, false)) {
            val scratch = if (table) EvaluationScratch() else null
            val match: (HttpPath) -> Unit = { pattern.match(it, scratch) }
            repeat(WARMUP) {
                match(short)
                match(long)
            }
            val ratio = best { repeat(REPEATS) { match(long) } } / best { repeat(REPEATS) { match(short) } }
            println("SegmentGlobCostTest: $shape, table=$table: 2,048 characters cost $ratio times 256")
            ratio.assert().describedAs(
                "$shape, table=$table: 2,048 characters cost $ratio times 256"
            ).isLessThan(MAX_RATIO)
        }
    }

    private fun best(batch: () -> Unit): Double = (1..BATCHES).minOf {
        val start = System.nanoTime()
        batch()
        System.nanoTime() - start
    }.toDouble()

    private fun value(shape: String, length: Int): String = when (shape) {
        "alternating" -> "/" + "a".repeat(length - 1) + "b"
        "captures" -> "/x" + "a".repeat(length - 1)
        else -> "/" + "a".repeat(length)
    }

    private companion object {
        const val SHORT = 256
        const val LONG = 2047
        const val WARMUP = 500
        const val BATCHES = 15
        const val REPEATS = 50
        const val MAX_RATIO = 32.0

        val PATTERNS = mapOf(
            "literal" to "/*ab*",
            "long-literal" to "/*" + "a".repeat(100) + "b*",
            "alternating" to "/{v}a*a*a*a*a*a*a*b",
            "captures" to "/*{a}*{c}*{d}*{e}*{f}*{g}*{h}x{v}",
        )
    }
}
