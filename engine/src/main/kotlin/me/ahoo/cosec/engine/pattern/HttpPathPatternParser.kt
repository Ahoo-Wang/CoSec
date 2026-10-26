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

/** Parses an [HttpPathPattern]: its segments, the same way Spring's `InternalPathPatternParser` classifies them. */
// Every rejection throws where it is found: `throw invalid(...)` rather than a `Nothing` helper, so coverage sees the line.
@Suppress("ThrowsCount")
internal class HttpPathPatternParser(private val pattern: String, private val caseSensitive: Boolean) {
    class Parsed(val elements: Array<PathElement>, val variableNames: List<String>)

    private val variableNames = ArrayList<String>()

    fun parse(): Parsed {
        if (pattern.isEmpty()) {
            throw invalid(0, "the pattern is empty")
        }
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
        return Parsed(elements.toTypedArray(), variableNames.toList())
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
                    tokens.add(if (char == '*') GlobToken.Run(-1) else GlobToken.AnyChar)
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

        fun matchesAt(value: String, offset: Int): Boolean {
            if (offset + text.length > value.length) {
                return false
            }
            for (index in text.indices) {
                val char = value[offset + index]
                if (char != text[index] && (caseSensitive || asciiLowercase(char) != text[index])) {
                    return false
                }
            }
            return true
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
 * Whether a segment matches is decided by [accepts], the two-pointer wildcard match: it backtracks only to the last
 * run, so it takes at most tokens × length steps and allocates nothing, whatever the request path holds (review F4 of
 * #1040: the backtracking below took 0.2–0.4 s on one 8 KB segment). Only a segment that matches is then captured by
 * backtracking the way the regular expression does, so every greedy run takes as much as the rest allows and captures
 * the same values; failed (token, position) pairs are remembered, so that takes at most tokens × length² steps.
 */
internal class SegmentGlob(private val tokens: Array<GlobToken>) {
    private val remembersFailures = tokens.count { it is GlobToken.Run } > 1

    fun matches(value: String, captures: Array<String?>?): Boolean {
        if (!accepts(value)) {
            return false
        }
        if (captures == null) {
            return true
        }
        val failed = if (remembersFailures) BooleanArray(tokens.size * (value.length + 1)) else null
        return step(0, 0, value, captures, failed)
    }

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

    private fun step(
        token: Int,
        position: Int,
        value: String,
        captures: Array<String?>?,
        failed: BooleanArray?,
    ): Boolean {
        if (token == tokens.size) {
            return position == value.length
        }
        val key = token * (value.length + 1) + position
        if (failed != null && failed[key]) {
            return false
        }
        val matched = when (val current = tokens[token]) {
            is GlobToken.Literal -> current.matchesAt(value, position) &&
                step(token + 1, position + current.length, value, captures, failed)

            GlobToken.AnyChar ->
                position < value.length &&
                    step(token + 1, nextCodePoint(value, position), value, captures, failed)

            is GlobToken.Run -> run(current, token, position, value, captures, failed)
        }
        if (!matched && failed != null) {
            failed[key] = true
        }
        return matched
    }

    private fun run(
        run: GlobToken.Run,
        token: Int,
        position: Int,
        value: String,
        captures: Array<String?>?,
        failed: BooleanArray?,
    ): Boolean {
        var end = value.length
        while (true) {
            if (step(token + 1, end, value, captures, failed)) {
                if (run.variable >= 0 && captures != null) {
                    captures[run.variable] = value.substring(position, end)
                }
                return true
            }
            if (end == position) {
                return false
            }
            end = previousCodePoint(value, end, position)
        }
    }

    private fun nextCodePoint(value: String, position: Int): Int =
        if (value[position].isHighSurrogate() && position + 1 < value.length && value[position + 1].isLowSurrogate()) {
            position + 2
        } else {
            position + 1
        }

    private fun previousCodePoint(value: String, end: Int, floor: Int): Int =
        if (end - 2 >= floor && value[end - 1].isLowSurrogate() && value[end - 2].isHighSurrogate()) {
            end - 2
        } else {
            end - 1
        }
}
