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

    // stays: a grammar; the depth follows its productions
    @Suppress("NestedBlockDepth")
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
                else -> {
                    if (isLoneSurrogate(text, index)) {
                        throw invalid(begin + index, "a lone surrogate half is no character a path can hold")
                    }
                    literal.append(char)
                }
            }
            index++
        }
        flush()
        return tokens
    }

    /**
     * Whether the character at [index] is half of a surrogate pair without its other half (review L4 of #1200). No
     * decoded path holds one (a malformed UTF-8 sequence decodes to U+FFFD), and Spring's regular expression reads the
     * pattern by code points, so such a literal would match the middle of a pair where Spring matches nothing.
     */
    private fun isLoneSurrogate(text: String, index: Int): Boolean {
        val char = text[index]
        return when {
            char.isHighSurrogate() -> index + 1 >= text.length || !text[index + 1].isLowSurrogate()
            char.isLowSurrogate() -> index == 0 || !text[index - 1].isHighSurrogate()
            else -> false
        }
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
