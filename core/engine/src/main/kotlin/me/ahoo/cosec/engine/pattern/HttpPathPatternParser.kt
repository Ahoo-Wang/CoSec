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
 * Parses an [HttpPathPattern]: its segments, the same way Spring's `InternalPathPatternParser` classifies them.
 *
 * Its cost is bounded (RC1-8, `DOC-4`): a pattern is at most [HttpPathPattern.MAX_LENGTH] characters, consecutive `*`
 * within a segment count once (`.*.*` matches what `.*` matches, and no capture changes), and a segment that mixes
 * wildcards or captures with other text holds at most [HttpPathPattern.MAX_SEGMENT_TOKENS] tokens. A glob segment then
 * takes at most tokens × length steps to match, and its capture recursion is that many frames deep.
 */
// Every rejection throws where it is found: `throw invalid(...)` rather than a `Nothing` helper, so coverage sees the line.
@Suppress("ThrowsCount")
internal class HttpPathPatternParser(private val pattern: String, private val caseSensitive: Boolean) {
    /** [globUnits]: what a document's budget counts for it (`DOC-4`, [HttpPathPattern]). */
    class Parsed(val elements: Array<PathElement>, val variableNames: List<String>, val globUnits: Int)

    private val variableNames = ArrayList<String>()

    fun parse(): Parsed {
        checkLength()
        val elements = ArrayList<PathElement>()
        var restAt = -1
        var begin = 0
        while (begin <= pattern.length) {
            val end = pattern.indexOf(HttpPath.SEPARATOR, begin).let { if (it < 0) pattern.length else it }
            if (end > begin) {
                val element = segment(begin, end)
                if (element is PathElement.Rest) {
                    if (restAt >= 0) {
                        throw invalid(begin, "only one ** or {*name} is allowed")
                    }
                    // The separator in front belongs to the rest, so `/a/**` matches `/a` (as in Spring).
                    if (elements.lastOrNull() === PathElement.Separator) {
                        elements.removeAt(elements.size - 1)
                    }
                    restAt = elements.size
                }
                elements.add(element)
            }
            if (end == pattern.length) {
                break
            }
            elements.add(PathElement.Separator)
            begin = end + 1
        }
        if (restAt > 0 && restAt != elements.size - 1) {
            throw invalid(0, "** and {*name} must be at the start or the end of the pattern")
        }
        val globUnits = elements.sumOf { (it as? PathElement.Glob)?.glob?.units ?: 0 } * if (restAt == 0) 2 else 1
        return Parsed(elements.toTypedArray(), variableNames.toList(), globUnits)
    }

    private fun checkLength() {
        if (pattern.isEmpty()) {
            throw invalid(0, "the pattern is empty")
        }
        if (pattern.length > HttpPathPattern.MAX_LENGTH) {
            throw invalid(
                HttpPathPattern.MAX_LENGTH,
                "the pattern is ${pattern.length} characters, more than ${HttpPathPattern.MAX_LENGTH}: no path is longer",
            )
        }
    }

    private fun segment(begin: Int, end: Int): PathElement {
        val text = pattern.substring(begin, end)
        when {
            text == "**" -> return PathElement.Rest(-1)
            text == "*" -> return PathElement.Wildcard
            text.startsWith("{*") -> return captureRest(text, begin)
        }
        val tokens = tokens(text, begin)
        val single = tokens.singleOrNull()
        if (single is GlobToken.Run && single.variable >= 0) {
            return PathElement.Capture(single.variable)
        }
        if (tokens.any { it is GlobToken.Run }) {
            if (tokens.all { it is GlobToken.Run }) {
                throw invalid(begin, "segment [$text] can match an empty value; add a literal character or ?")
            }
            if (tokens.size > HttpPathPattern.MAX_SEGMENT_TOKENS) {
                throw invalid(
                    begin,
                    "segment [$text] has ${tokens.size} parts (*, ?, {name} and the text between them), more than " +
                        "${HttpPathPattern.MAX_SEGMENT_TOKENS}",
                )
            }
            return PathElement.Glob(SegmentGlob(tokens.toTypedArray()))
        }
        if (tokens.any { it === GlobToken.AnyChar }) {
            return PathElement.SingleChar(text, caseSensitive)
        }
        return PathElement.Literal(text, caseSensitive)
    }

    private fun captureRest(text: String, begin: Int): PathElement.Rest {
        if (!text.endsWith('}') || text.indexOf('}') != text.length - 1 || text.indexOf('{', 1) >= 0) {
            throw invalid(begin, "{*name} must be a whole segment")
        }
        return PathElement.Rest(variable(text.substring(2, text.length - 1), begin + 2))
    }

    private fun tokens(text: String, begin: Int): List<GlobToken> {
        val tokens = ArrayList<GlobToken>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                tokens.add(GlobToken.Literal(literal.toString(), caseSensitive))
                literal.clear()
            }
        }
        var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                '*', '?' -> {
                    flush()
                    val repeatsAStar = char == '*' && (tokens.lastOrNull() as? GlobToken.Run)?.variable == -1
                    if (!repeatsAStar) {
                        tokens.add(if (char == '*') GlobToken.Run(-1) else GlobToken.AnyChar)
                    }
                }

                '{' -> {
                    flush()
                    val close = text.indexOf('}', index + 1)
                    if (close < 0) {
                        throw invalid(begin + index, "{ is not closed")
                    }
                    tokens.add(GlobToken.Run(variable(text.substring(index + 1, close), begin + index + 1)))
                    index = close
                }

                '}' -> throw invalid(begin + index, "} is not opened")
                else -> literal.append(char)
            }
            index++
        }
        flush()
        return tokens
    }

    /** Registers the variable [name] and returns its index. */
    private fun variable(name: String, at: Int): Int {
        for (index in name.indices) {
            when (name[index]) {
                ':' -> throw invalid(at + index, "{name:regex} is not supported; capture {name} and add a condition")
                '{' -> throw invalid(at + index, "captures do not nest")
                '*' -> throw invalid(at + index, "{*name} must be a whole segment")
            }
        }
        VariableNames.invalid(name)?.let { throw invalid(at, it) }
        if (name in variableNames) {
            throw invalid(at, "variable [$name] is captured twice")
        }
        variableNames.add(name)
        return variableNames.size - 1
    }

    private fun invalid(index: Int, reason: String) = InvalidPatternException(pattern, index, reason)
}

/** Variable names follow Spring's rule, except that they must not be empty. */
internal object VariableNames {
    /** Why [name] is not a variable name, or `null` if it is one. */
    fun invalid(name: String): String? {
        if (name.isEmpty()) {
            return "a capture needs a name"
        }
        if (!name[0].isJavaIdentifierStart()) {
            return "a variable name cannot start with '${name[0]}'"
        }
        val illegal = name.firstOrNull { it != '-' && !it.isJavaIdentifierPart() } ?: return null
        return "'$illegal' is not allowed in a variable name"
    }
}

/** A part of a [SegmentGlob]. */
internal sealed class GlobToken {
    /** Literal text; case-insensitive in ASCII only, as Spring's regular expression compares it. */
    class Literal(text: String, private val caseSensitive: Boolean) : GlobToken() {
        private val text: String = if (caseSensitive) text else asciiLowercase(text)
        val length: Int get() = text.length

        /** Whether [char] can start it: the first test of [matchesAt], cheap enough to make before calling it. */
        fun startsWith(char: Char): Boolean = char == text[0] || (!caseSensitive && asciiLowercase(char) == text[0])

        fun matchesAt(value: String, offset: Int): Boolean {
            val text = text
            if (offset + text.length > value.length) {
                return false
            }
            if (!caseSensitive) {
                for (index in text.indices) {
                    val char = value[offset + index]
                    if (char != text[index] && asciiLowercase(char) != text[index]) {
                        return false
                    }
                }
                return true
            }
            // The JDK compares longer regions a vector at a time; for a few characters its checks cost more.
            if (text.length >= VECTOR_LENGTH) {
                return value.regionMatches(offset, text, 0, text.length)
            }
            for (index in text.indices) {
                if (value[offset + index] != text[index]) {
                    return false
                }
            }
            return true
        }

        private companion object {
            const val VECTOR_LENGTH = 16
        }
    }

    /** `?`: one code point. */
    object AnyChar : GlobToken()

    /** `*` (no [variable]) or `{name}`: any number of code points, as many as the rest of the segment leaves. */
    class Run(val variable: Int) : GlobToken()

    companion object {
        fun asciiLowercase(char: Char): Char = if (char in 'A'..'Z') char + ('a' - 'A') else char

        fun asciiLowercase(text: String): String = String(CharArray(text.length) { asciiLowercase(text[it]) })
    }
}

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
 * value's length in steps. The table is the evaluation's [GlobScratch]: matching allocates only the captured values.
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
    fun matches(value: String, captures: Array<String?>?, scratch: GlobScratch?): Boolean =
        if (scratch == null && captures == null) accepts(value) else table(value, captures, scratch ?: GlobScratch())

    /**
     * Whether [value] matches. A run matches any number of code points; the other tokens match at one position each,
     * so after a mismatch only the last run needs to take one more code point: an earlier run taking more can only
     * leave the tokens after it a later start, which the last run already reaches.
     */
    @Suppress("ReturnCount")
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
    fun table(value: String, captures: Array<String?>?, scratch: GlobScratch): Boolean {
        val width = (value.length + WORD_BITS) ushr WORD_SHIFT
        val pairs = scratch.hasPairs(value)
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
        if (table[0] and 1L == 0L) {
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
                val holds = table[next + ((start + 2) ushr WORD_SHIFT)] and (1L shl (start + 2)) != 0L
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

/**
 * The table [SegmentGlob.table] works in, one per evaluation and reused across its statements and segments, so
 * matching allocates no table per statement. Not thread-safe: it belongs to the [ResourceTarget] of one evaluation.
 */
internal class GlobScratch {
    private var words: LongArray? = null

    // Every statement matches the same segment values of the request's path: the last one's answer is kept.
    private var scanned: String? = null
    private var pairs = false

    /** Whether [value] holds a surrogate pair. */
    fun hasPairs(value: String): Boolean {
        if (value !== scanned) {
            pairs = false
            for (position in 1 until value.length) {
                if (value[position - 1].isHighSurrogate() && value[position].isLowSurrogate()) {
                    pairs = true
                    break
                }
            }
            scanned = value
        }
        return pairs
    }

    /** At least [size] words, with unspecified contents. */
    fun words(size: Int): LongArray {
        val current = words
        if (current != null && current.size >= size) {
            return current
        }
        return LongArray(maxOf(size, (current?.size ?: 0) * 2)).also { words = it }
    }
}
