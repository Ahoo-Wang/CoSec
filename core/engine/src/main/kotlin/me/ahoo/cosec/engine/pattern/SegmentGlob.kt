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

package me.ahoo.cosec.engine.pattern

/**
 * A segment with `*` or captures among other text. Spring compiles it into a regular expression (`*` is `.*`, `?` is
 * `.`, `{name}` is `(.*)`, with `DOTALL`) and calls `matches`.
 *
 * An evaluation matches it with [table], a dynamic programme (review M2 of #1200). Row `t` of its table says, for each
 * position, whether tokens `t..` match the rest of the value; each row comes from the next, so a row of a run or `?`
 * takes a few word operations, and a literal is compared only where the row after it holds. A segment whose values a
 * condition reads is then captured by a walk forward that gives each run the largest end whose next row holds: as much
 * as the rest allows, the greedy choice the regular expression's backtracking makes, so the values are the same. A
 * literal compared at every position costs its length each time, so a segment costs at most its [units] times the
 * value's length in steps. The table is the evaluation's ([EvaluationScratch]): matching allocates only the captured values.
 *
 * Without a scratch table, as through the public [HttpPathPattern.matches], [accepts] decides instead: the two-pointer
 * wildcard match, which goes back only to the last run and allocates nothing (review F4 of #1040: backtracking took
 * 0.2–0.4 s on one 8 KB segment), in the same bound with a larger constant.
 */
internal class SegmentGlob(private val tokens: Array<GlobToken>) {
    /** How many tokens it has: at most [HttpPathPattern.MAX_SEGMENT_TOKENS]. */
    val size: Int get() = tokens.size

    /** What it costs a document's budget (`DOC-4`): its tokens and the characters of its literals. */
    val units: Int = tokens.size + tokens.sumOf { (it as? GlobToken.Literal)?.length ?: 0 }

    /** Whether [value] matches; when it does and [captures] is given, the runs' values in it. */
    fun matches(value: String, captures: Array<String?>?, scratch: EvaluationScratch?): Boolean {
        if (scratch == null && captures == null) {
            return accepts(value)
        }
        return table(value, captures, scratch ?: EvaluationScratch())
    }

    /**
     * Whether [value] matches. A run matches any number of code points; the other tokens match at one position each,
     * so after a mismatch only the last run needs to take one more code point: an earlier run taking more can only
     * leave the tokens after it a later start, which the last run already reaches.
     */
    // stays: a grammar; the depth follows its productions
    @Suppress("NestedBlockDepth", "ReturnCount")
    fun accepts(value: String): Boolean {
        var token = 0
        var position = 0
        var lastRun = -1
        var runEnd = 0
        while (position < value.length || token < tokens.size) {
            if (token < tokens.size) {
                when (val current = tokens[token]) {
                    is GlobToken.Run -> {
                        lastRun = token
                        runEnd = position
                        token++
                        continue
                    }

                    is GlobToken.Literal -> if (current.matchesAt(value, position)) {
                        position += current.length
                        token++
                        continue
                    }

                    GlobToken.AnyChar -> if (position < value.length) {
                        position = nextCodePoint(value, position)
                        token++
                        continue
                    }
                }
            }
            if (lastRun < 0 || runEnd >= value.length) {
                return false
            }
            runEnd = nextCodePoint(value, runEnd)
            position = runEnd
            token = lastRun + 1
        }
        return true
    }

    /**
     * Whether [value] matches, capturing the runs' values into [captures] when it does and they are given.
     *
     * Positions are `0..length`, one bit each; a row is `width` words. A run ends only where a code point does: the
     * regular expression steps back by code points, so the middle of a surrogate pair is no end, unless the run starts
     * there (`boundaries` is the row of the other positions, built only when the value has a pair).
     */
    @Suppress("ReturnCount")
    fun table(value: String, captures: Array<String?>?, scratch: EvaluationScratch): Boolean {
        val width = (value.length + WORD_BITS) ushr WORD_SHIFT
        val pairs = hasPairs(value)
        val table = scratch.words(width * (tokens.size + if (pairs) 2 else 1))
        val boundaries = if (pairs) boundaries(value, table, (tokens.size + 1) * width, width) else NO_ROW
        val end = tokens.size * width
        table.fill(0L, end, end + width)
        table[end + (value.length ushr WORD_SHIFT)] = 1L shl value.length
        for (token in tokens.indices.reversed()) {
            val row = token * width
            when (val current = tokens[token]) {
                is GlobToken.Literal -> literalRow(current, value, table, row, width)
                GlobToken.AnyChar -> anyCharRow(value, table, row, width, pairs)
                is GlobToken.Run -> runRow(table, row, width, boundaries)
            }
        }
        if ((table[0] and 1L) == 0L) {
            return false
        }
        if (captures == null) {
            return true
        }
        var position = 0
        for (token in tokens.indices) {
            when (val current = tokens[token]) {
                is GlobToken.Literal -> position += current.length
                GlobToken.AnyChar -> position = nextCodePoint(value, position)
                is GlobToken.Run -> {
                    // The row after the run holds at `end`; it holds at `position` when no later end is possible.
                    val end = maxOf(position, highest(table, (token + 1) * width, width, boundaries))
                    if (current.variable >= 0) {
                        captures[current.variable] = value.substring(position, end)
                    }
                    position = end
                }
            }
        }
        return true
    }

    /** Row `p`: the literal matches at `p` and the next row holds where it ends. */
    private fun literalRow(literal: GlobToken.Literal, value: String, table: LongArray, row: Int, width: Int) {
        table.fill(0L, row, row + width)
        val next = row + width
        val single = literal.length == 1
        for (word in 0 until width) {
            var bits = table[next + word]
            while (bits != 0L) {
                val start = (word shl WORD_SHIFT) + bits.countTrailingZeroBits() - literal.length
                bits = bits and (bits - 1)
                val starts = start >= 0 && literal.startsWith(value[start])
                if (starts && (single || literal.matchesAt(value, start))) {
                    table[row + (start ushr WORD_SHIFT)] = table[row + (start ushr WORD_SHIFT)] or (1L shl start)
                }
            }
        }
    }

    /** Row `p`: a code point starts at `p` and the next row holds after it. */
    private fun anyCharRow(value: String, table: LongArray, row: Int, width: Int, pairs: Boolean) {
        val next = row + width
        for (word in 0 until width) {
            val carry = if (word + 1 < width) table[next + word + 1] shl (WORD_BITS - 1) else 0L
            table[row + word] = (table[next + word] ushr 1) or carry
        }
        if (!pairs) {
            return
        }
        // A pair is one code point: from its first half, the next row is read two characters on.
        for (start in 0 until value.length - 1) {
            if (value[start].isHighSurrogate() && value[start + 1].isLowSurrogate()) {
                val holds = (table[next + ((start + 2) ushr WORD_SHIFT)] and (1L shl (start + 2))) != 0L
                val word = row + (start ushr WORD_SHIFT)
                table[word] = if (holds) table[word] or (1L shl start) else table[word] and (1L shl start).inv()
            }
        }
    }

    /** Row `p`: the next row holds at `p` or at a boundary after it, so at every `p` up to its highest boundary. */
    private fun runRow(table: LongArray, row: Int, width: Int, boundaries: Int) {
        val next = row + width
        val highest = highest(table, next, width, boundaries)
        for (word in 0 until width) {
            val below = when {
                highest < 0 || word > (highest ushr WORD_SHIFT) -> 0L
                word < (highest ushr WORD_SHIFT) -> -1L
                else -> -1L ushr (WORD_BITS - 1 - (highest and (WORD_BITS - 1)))
            }
            table[row + word] = table[next + word] or below
        }
    }

    /** The highest position where the row at [row] holds and a run may end, or -1. */
    private fun highest(table: LongArray, row: Int, width: Int, boundaries: Int): Int {
        for (word in width - 1 downTo 0) {
            val bits = table[row + word] and if (boundaries == NO_ROW) -1L else table[boundaries + word]
            if (bits != 0L) {
                return (word shl WORD_SHIFT) + WORD_BITS - 1 - bits.countLeadingZeroBits()
            }
        }
        return -1
    }

    /** Fills the row at [row] with every position but the middle of a pair, and returns [row]. */
    private fun boundaries(value: String, table: LongArray, row: Int, width: Int): Int {
        table.fill(-1L, row, row + width)
        for (position in 1 until value.length) {
            if (value[position - 1].isHighSurrogate() && value[position].isLowSurrogate()) {
                table[row + (position ushr WORD_SHIFT)] = table[row + (position ushr WORD_SHIFT)] and
                    (1L shl position).inv()
            }
        }
        return row
    }

    /**
     * Whether [value] holds a surrogate pair: then it counts fewer code points than characters. A Latin-1 string, as
     * every ASCII path is, holds none, and the JDK answers that without reading it.
     */
    private fun hasPairs(value: String): Boolean = value.codePointCount(0, value.length) < value.length

    private fun nextCodePoint(value: String, position: Int): Int =
        if (value[position].isHighSurrogate() && position + 1 < value.length && value[position + 1].isLowSurrogate()) {
            position + 2
        } else {
            position + 1
        }

    private companion object {
        const val WORD_BITS = 64
        const val WORD_SHIFT = 6
        const val NO_ROW = -1
    }
}
