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
 * A path pattern of the `http` namespace (RFC 0005 §5.2), in pure Kotlin.
 *
 * It matches what Spring's `PathPattern` (the `spring-web` version `main` uses) matches and captures the same values,
 * on this subset: literal segments, `?` (one character), `*` within a segment, `*` as a segment, `{name}` (one segment,
 * captured), `**` and `{*name}` (any number of segments, at the start or the end of the pattern), and the options
 * [caseSensitive] and [decodeAndParseSegments]. `HttpPathPatternParityTest` in `cosec-core` compares the two.
 *
 * These compile in Spring but not here, so a policy that uses them fails to convert instead of matching differently:
 * - `{name:regex}`: a regular expression has no static cost bound, and `java.util.regex` is not available here.
 * - the empty pattern, which Spring matches against `""` and `/`.
 * - a segment that can match an empty value, other than `*` alone: only `*`s and captures, as in `***`, `{a}{b}` or
 *   `*{a}`. Spring matches these against separators, so `/{a}{b}/x` matches `///x`, and fails with a
 *   `ClassCastException` while capturing.
 * - `{}` and `{*}`, captures without a name: the condition that reads the variable has nothing to name it by.
 *
 * Captured values are the segments' decoded values. They only ever reach conditions as values: a value never becomes
 * part of a pattern (RFC 0005 §2 J9), so a path template is a capture compared with `eq`.
 */
class HttpPathPattern private constructor(
    val pattern: String,
    val caseSensitive: Boolean,
    val decodeAndParseSegments: Boolean,
    private val elements: Array<PathElement>,
    /** The names of the captured variables, in the order [Captures] holds their values. */
    val variableNames: List<String>,
) {
    private val names: Array<String> = variableNames.toTypedArray()

    /** `**` or `{*name}` as the first of several elements: the rest of the pattern matches the end of the path. */
    private val leadingRest: Boolean = elements.size > 1 && elements[0] is PathElement.Rest

    /** Spring matches the empty path only with a pattern that starts with `**` or `{*name}`. */
    private val matchesEmptyPath: Boolean = elements[0] is PathElement.Rest

    /**
     * Without a leading rest, element `i` of the pattern matches element `i` of the path, so the literal segments can
     * be checked before any capture is allocated: most paths a statement sees differ in a literal.
     */
    private val literals: IntArray =
        if (leadingRest) IntArray(0) else elements.indices.filter { elements[it] is PathElement.Literal }.toIntArray()

    /**
     * The elements every path this pattern matches starts with, for the candidate index (RFC 0005 §6.2): `null` for a
     * separator, else a segment's value. They are the pattern's leading separators and case-sensitive literal
     * segments, so element `i` of a matching path is element `i` here. Empty when the pattern starts with `**` or
     * `{*name}`, or does not decode: then those positions do not hold.
     */
    internal val prefix: Array<String?> = prefixOf(elements, leadingRest || !decodeAndParseSegments)

    /** Whether [path] matches. Allocates nothing unless a segment has several `*` or captures. */
    fun matches(path: HttpPath): Boolean = matchPath(path, null)

    /** The captured variables if [path] matches, otherwise `null`. */
    fun match(path: HttpPath): Captures? {
        if (names.isEmpty()) {
            return if (matchPath(path, null)) Captures.NONE else null
        }
        if (!literalsMatch(path)) {
            return null
        }
        val values = arrayOfNulls<String>(names.size)
        if (!matchPath(path, values)) {
            return null
        }
        return Captures(names, values.requireNoNulls())
    }

    private fun literalsMatch(path: HttpPath): Boolean {
        for (position in literals) {
            val segment = if (position < path.size) path.segment(position) else null
            if (segment == null || !(elements[position] as PathElement.Segment).matches(segment, null)) {
                return false
            }
        }
        return true
    }

    private fun matchPath(path: HttpPath, captures: Array<String?>?): Boolean {
        require(path.decodeAndParseSegments == decodeAndParseSegments) {
            "Path [$path] was parsed with decodeAndParseSegments=${path.decodeAndParseSegments}, pattern [$pattern] " +
                "needs $decodeAndParseSegments."
        }
        if (path.value.isEmpty() && !matchesEmptyPath) {
            return false
        }
        if (!leadingRest) {
            return matchFrom(0, 0, path, captures)
        }
        // Every element after a leading rest matches one path element, except a final `*`, which may match none:
        // so the rest of the pattern can only start at one of the last two positions. Spring takes the first that fits.
        val rest = elements.size - 1
        for (start in maxOf(0, path.size - rest) until path.size) {
            if (matchFrom(1, start, path, captures)) {
                (elements[0] as PathElement.Rest).capture(path, 0, start, captures)
                return true
            }
        }
        return false
    }

    private fun matchFrom(first: Int, start: Int, path: HttpPath, captures: Array<String?>?): Boolean {
        var index = start
        val last = elements.size - 1
        for (position in first..last) {
            index = when (val element = elements[position]) {
                is PathElement.Rest -> return element.matches(path, index, captures)
                PathElement.Separator -> if (index < path.size && path.isSeparator(index)) index + 1 else NO_MATCH
                PathElement.Wildcard -> wildcard(path, index, position == last)
                is PathElement.Segment -> {
                    val segment = if (index < path.size) path.segment(index) else null
                    if (segment != null && element.matches(segment, captures)) index + 1 else NO_MATCH
                }
            }
            if (index == NO_MATCH) {
                return false
            }
        }
        return index == path.size
    }

    /** A final `*` also matches no segment at all, so `/a/` matches; within the pattern it needs a character. */
    private fun wildcard(path: HttpPath, index: Int, last: Boolean): Int {
        if (index >= path.size) {
            return if (last) index else NO_MATCH
        }
        val segment = path.segment(index) ?: return NO_MATCH
        return if (last || segment.isNotEmpty()) index + 1 else NO_MATCH
    }

    override fun equals(other: Any?): Boolean =
        other is HttpPathPattern &&
            other.pattern == pattern &&
            other.caseSensitive == caseSensitive &&
            other.decodeAndParseSegments == decodeAndParseSegments

    override fun hashCode(): Int = (pattern.hashCode() * 31 + caseSensitive.hashCode()) * 31 +
        decodeAndParseSegments.hashCode()

    override fun toString(): String = pattern

    companion object {
        private const val NO_MATCH = -1

        private fun prefixOf(elements: Array<PathElement>, none: Boolean): Array<String?> {
            if (none) {
                return arrayOfNulls(0)
            }
            val prefix = ArrayList<String?>()
            for (element in elements) {
                when {
                    element === PathElement.Separator -> prefix.add(null)
                    element is PathElement.Literal && element.caseSensitive -> prefix.add(element.text)
                    else -> break
                }
            }
            return prefix.toTypedArray()
        }

        /**
         * Compiles [pattern], or throws [InvalidPatternException]: a pattern outside the subset never compiles into one
         * that matches more, or less, than it says.
         */
        fun compile(
            pattern: String,
            caseSensitive: Boolean = true,
            decodeAndParseSegments: Boolean = true,
        ): HttpPathPattern {
            val parsed = HttpPathPatternParser(pattern, caseSensitive).parse()
            return HttpPathPattern(
                pattern = pattern,
                caseSensitive = caseSensitive,
                decodeAndParseSegments = decodeAndParseSegments,
                elements = parsed.elements,
                variableNames = parsed.variableNames,
            )
        }
    }
}

/** What a pattern captured, by variable index. Values are never `null`: a variable that matched nothing is empty. */
class Captures internal constructor(private val names: Array<String>, private val values: Array<String>) {
    val size: Int get() = names.size

    fun name(index: Int): String = names[index]

    fun value(index: Int): String = values[index]

    /** The value of the variable [name], or `null` if the pattern has no such variable. */
    operator fun get(name: String): String? {
        val index = names.indexOf(name)
        return if (index < 0) null else values[index]
    }

    fun toMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>(names.size)
        for (index in names.indices) {
            map[names[index]] = values[index]
        }
        return map
    }

    override fun toString(): String = toMap().toString()

    companion object {
        val NONE: Captures = Captures(emptyArray(), emptyArray())
    }
}

/** One element of a compiled [HttpPathPattern]; each matches one element of an [HttpPath], except [Rest]. */
internal sealed class PathElement {
    object Separator : PathElement()

    /** `*` as a whole segment. */
    object Wildcard : PathElement()

    /** `**` (no [variable]) or `{*name}`, with the separator in front of it. */
    class Rest(private val variable: Int) : PathElement() {
        /** At the end of the pattern: the rest of the path is empty or starts with a separator. */
        fun matches(path: HttpPath, index: Int, captures: Array<String?>?): Boolean {
            if (index < path.size && !path.isSeparator(index)) {
                return false
            }
            capture(path, index, path.size, captures)
            return true
        }

        fun capture(path: HttpPath, from: Int, to: Int, captures: Array<String?>?) {
            if (variable >= 0 && captures != null) {
                captures[variable] = path.join(from, to)
            }
        }
    }

    /** An element that matches one segment by its value. */
    sealed class Segment : PathElement() {
        abstract fun matches(value: String, captures: Array<String?>?): Boolean
    }

    /** Compared like `String.equalsIgnoreCase` when case-insensitive, as Spring compares literal segments. */
    class Literal(val text: String, val caseSensitive: Boolean) : Segment() {
        override fun matches(value: String, captures: Array<String?>?): Boolean =
            value.equals(text, ignoreCase = !caseSensitive)
    }

    /** A segment with `?` and no `*` or capture: `?` is one UTF-16 character; case folds per character. */
    class SingleChar(private val text: String, private val caseSensitive: Boolean) : Segment() {
        override fun matches(value: String, captures: Array<String?>?): Boolean {
            if (value.length != text.length) {
                return false
            }
            for (index in text.indices) {
                val expected = text[index]
                if (expected == '?') {
                    continue
                }
                val actual = value[index]
                if (caseSensitive) {
                    if (actual != expected) return false
                } else if (actual.lowercaseChar() != expected.lowercaseChar()) {
                    return false
                }
            }
            return true
        }
    }

    /** `{name}` as a whole segment: any non-empty value. */
    class Capture(private val variable: Int) : Segment() {
        override fun matches(value: String, captures: Array<String?>?): Boolean {
            if (value.isEmpty()) {
                return false
            }
            if (captures != null) {
                captures[variable] = value
            }
            return true
        }
    }

    /** A segment with `*` or a capture among other text: see [SegmentGlob]. */
    class Glob(private val glob: SegmentGlob) : Segment() {
        override fun matches(value: String, captures: Array<String?>?): Boolean = glob.matches(value, captures)
    }
}
