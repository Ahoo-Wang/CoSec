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

import me.ahoo.cosec.model.condition.Containment

/**
 * What [PatternContainment.contains] proved about two resource patterns.
 */
sealed interface ResourceContainment {
    val answer: Containment

    /**
     * Every resource the inner pattern matches, the outer one matches. [variables] maps a variable of the outer pattern
     * to the variable of the inner one that captures the same value on every such resource; a variable of the outer
     * pattern that no variable of the inner one corresponds to is absent.
     */
    class Contained(val variables: Map<String, String>) : ResourceContainment {
        override val answer: Containment get() = Containment.YES
    }

    /** Not proven. */
    class Unknown(val reason: String) : ResourceContainment {
        override val answer: Containment get() = Containment.UNKNOWN
    }
}

/**
 * Containment and disjointness of action and resource patterns (RFC 0005 §5.5, RFC 0007 §3.1 N7), for the grant
 * check of the control plane (RFC 0007 §7.4.3).
 *
 * Both are sound and deliberately incomplete: [contains] answers [Containment.YES] only when every action or resource
 * the inner pattern matches, the outer pattern matches too (property A7), and [disjoint] answers `true` only when no
 * action or resource matches both. Whatever they cannot prove is "not contained" and "may overlap".
 *
 * Resource patterns are compared segment by segment: a literal is contained in the same literal, a literal, `*` or
 * `{x}` in `*` or `{x}`, and a final `**` (or `{*x}`) contains whatever follows its position. The rest of `http:path:`
 * patterns is compared only when written identically:
 * - a segment with `?`, a `*` among other text, or a capture among other text matches what its text says, under
 *   rules that differ between case options and code points; it is contained only in the same segment;
 * - patterns with different case or decoding options parse and fold paths differently, and a leading `**` matches the
 *   end of the path; such patterns are contained only in themselves;
 * - a final `*` also matches an empty last segment and no segment at all (`/a/` followed by `*` matches `/a/`), so it is contained
 *   only in a final `*`.
 *
 * Literals compare by their exact text, case included, also in case-insensitive patterns: equal text is the only
 * equality case folding cannot break.
 */
object PatternContainment {
    /** Whether every action [inner] matches, [outer] matches: `service:verb ⊑ service:* ⊑ *`. */
    fun contains(outer: ActionPattern, inner: ActionPattern): Containment {
        val contained = when {
            outer.service == null -> true
            outer.service != inner.service -> false
            else -> outer.verb == null || outer.verb == inner.verb
        }
        return if (contained) Containment.YES else Containment.UNKNOWN
    }

    /** Whether no action matches both: different services, or the same service and different verbs. */
    fun disjoint(a: ActionPattern, b: ActionPattern): Boolean {
        if (a.service == null || b.service == null) {
            return false
        }
        return a.service != b.service || (a.verb != null && b.verb != null && a.verb != b.verb)
    }

    /** Whether every resource [inner] matches, [outer] matches, and which variables capture the same values. */
    fun contains(outer: ResourcePattern, inner: ResourcePattern): ResourceContainment = when {
        outer is ResourcePattern.AnyResource -> ResourceContainment.Contained(emptyMap())
        inner is ResourcePattern.AnyResource -> unknown("only * contains every resource")
        outer is ResourcePattern.Typed && inner is ResourcePattern.Typed ->
            typed(TypedShape.of(outer), TypedShape.of(inner))

        outer is ResourcePattern.Http && inner is ResourcePattern.Http -> http(outer.path, inner.path)
        else -> unknown("an http:path: pattern and a typed pattern match different resource types")
    }

    /**
     * Whether no resource matches both: different resource types, or, at the same position, two literal segments that
     * differ. Case-insensitive `http:path:` patterns are never disjoint by their literals.
     */
    fun disjoint(a: ResourcePattern, b: ResourcePattern): Boolean = when {
        a is ResourcePattern.AnyResource || b is ResourcePattern.AnyResource -> false
        a is ResourcePattern.Typed && b is ResourcePattern.Typed -> typedDisjoint(TypedShape.of(a), TypedShape.of(b))
        a is ResourcePattern.Http && b is ResourcePattern.Http -> httpDisjoint(a.path, b.path)
        // `http:path:` patterns match only resources of type `http`, a type no typed pattern may name.
        else -> true
    }

    private fun unknown(reason: String) = ResourceContainment.Unknown(reason)

    private fun typed(outer: TypedShape, inner: TypedShape): ResourceContainment {
        if (outer.type != inner.type) {
            return unknown("the resource types differ")
        }
        val fixed = outer.segments.size
        val fits = if (outer.rest) {
            inner.segments.size >= fixed
        } else {
            !inner.rest && inner.segments.size == fixed
        }
        if (!fits) {
            return unknown("$inner has segments that $outer does not")
        }
        val variables = LinkedHashMap<String, String>()
        for (index in 0 until fixed) {
            val o = outer.segments[index]
            val i = inner.segments[index]
            val contained = when (o) {
                is Part.Literal -> i is Part.Literal && i.text == o.text
                // Every segment of a typed pattern matches only non-empty values.
                Part.Wildcard, is Part.Capture -> true
                else -> false
            }
            if (!contained) {
                return unknown("segment ${index + 1} of $inner is not contained in that of $outer")
            }
            if (o is Part.Capture && i is Part.Capture) {
                variables[o.name] = i.name
            }
        }
        return ResourceContainment.Contained(variables)
    }

    private fun typedDisjoint(a: TypedShape, b: TypedShape): Boolean {
        if (a.type != b.type) {
            return true
        }
        for (index in 0 until minOf(a.segments.size, b.segments.size)) {
            val x = a.segments[index]
            val y = b.segments[index]
            if (x is Part.Literal && y is Part.Literal && x.text != y.text) {
                return true
            }
        }
        return false
    }

    private fun http(outer: HttpPathPattern, inner: HttpPathPattern): ResourceContainment {
        if (outer.caseSensitive != inner.caseSensitive || outer.decodeAndParseSegments != inner.decodeAndParseSegments) {
            return unknown("$outer and $inner have different path options")
        }
        if (outer.pattern == inner.pattern) {
            return ResourceContainment.Contained(inner.variableNames.associateWith { it })
        }
        val o = HttpShape.of(outer) ?: return unknown("$outer has a structure this analysis does not know")
        val i = HttpShape.of(inner) ?: return unknown("$inner has a structure this analysis does not know")
        if (o.leadingRest || i.leadingRest) {
            return unknown("a leading ** is contained only in the same pattern")
        }
        return shapes(o.parts, i.parts, "$outer", "$inner")
    }

    /** Containment of two patterns without a leading rest, element by element. */
    private fun shapes(
        outer: List<Part>,
        inner: List<Part>,
        outerText: String,
        innerText: String
    ): ResourceContainment {
        val rest = outer.last() is Part.Rest
        val fixed = if (rest) outer.size - 1 else outer.size
        if (!fits(inner, fixed, rest)) {
            return unknown("$innerText has elements that $outerText does not")
        }
        val variables = LinkedHashMap<String, String>()
        for (index in 0 until fixed) {
            val o = outer[index]
            val i = inner[index]
            if (!element(o, i, outerLast = !rest && index == outer.size - 1, innerLast = index == inner.size - 1)) {
                return unknown("element ${index + 1} of $innerText is not contained in that of $outerText")
            }
            if (o is Part.Capture && i is Part.Capture) {
                variables[o.name] = i.name
            }
        }
        val o = (outer.getOrNull(fixed) as? Part.Rest)?.name
        val i = (inner.getOrNull(fixed) as? Part.Rest)?.name
        if (o != null && i != null) {
            variables[o] = i
        }
        return ResourceContainment.Contained(variables)
    }

    /**
     * Whether [inner] has the outer pattern's [fixed] elements and nothing it does not match after them. A final rest
     * (`**` after `/a`) matches `/a` and `/a/...`: the inner pattern ends there, goes on with a separator, or has its
     * own final rest there.
     */
    private fun fits(inner: List<Part>, fixed: Int, rest: Boolean): Boolean = if (rest) {
        inner.size == fixed || (inner.size > fixed && inner[fixed] == Part.Separator) ||
            (inner.size == fixed + 1 && inner[fixed] is Part.Rest)
    } else {
        inner.size == fixed
    }

    /** Whether the path element [inner] matches is always one [outer] matches, at the same position. */
    private fun element(outer: Part, inner: Part, outerLast: Boolean, innerLast: Boolean): Boolean = when (outer) {
        Part.Separator -> inner == Part.Separator
        is Part.Literal -> inner is Part.Literal && inner.text == outer.text
        is Part.Other -> inner is Part.Other && inner.text == outer.text
        // `*` and `{x}` match any non-empty segment; a final `*` also an empty one, or none.
        Part.Wildcard, is Part.Capture -> when (inner) {
            is Part.Literal, is Part.Capture, is Part.Other -> true
            Part.Wildcard -> !innerLast || (outer == Part.Wildcard && outerLast)
            else -> false
        }

        is Part.Rest -> false
    }

    private fun httpDisjoint(a: HttpPathPattern, b: HttpPathPattern): Boolean {
        if (!a.caseSensitive || !b.caseSensitive || a.decodeAndParseSegments != b.decodeAndParseSegments) {
            return false
        }
        val x = HttpShape.of(a) ?: return false
        val y = HttpShape.of(b) ?: return false
        if (x.leadingRest || y.leadingRest) {
            return false
        }
        // Every element before the first rest or final `*` matches exactly one path element, so both patterns compare
        // the same path element at each position up to there.
        for (index in 0 until minOf(x.parts.size, y.parts.size)) {
            val p = x.parts[index]
            val q = y.parts[index]
            if (p is Part.Rest || q is Part.Rest) {
                return false
            }
            if (p is Part.Literal && q is Part.Literal && p.text != q.text) {
                return true
            }
        }
        return false
    }

    /** One element of a pattern as this analysis sees it. */
    private sealed class Part {
        object Separator : Part()

        object Wildcard : Part()

        class Literal(val text: String) : Part()

        class Capture(val name: String) : Part()

        /** `**`, or `{*name}`. */
        class Rest(val name: String?) : Part()

        /** A segment with `?`, or with `*` or a capture among other text. */
        class Other(val text: String) : Part()
    }

    /** A typed pattern, re-read from its text: the text parsed, so it is well formed. */
    private class TypedShape(val type: String, val segments: List<Part>, val rest: Boolean, val text: String) {
        override fun toString(): String = text

        companion object {
            fun of(pattern: ResourcePattern.Typed): TypedShape {
                val text = pattern.toString()
                val parts = text.split('/')
                val segments = ArrayList<Part>()
                var rest = false
                for (index in 1 until parts.size) {
                    val part = parts[index]
                    when {
                        part == "**" && index == parts.size - 1 -> rest = true
                        part == "*" -> segments += Part.Wildcard
                        part.startsWith('{') -> segments += Part.Capture(part.substring(1, part.length - 1))
                        else -> segments += Part.Literal(part)
                    }
                }
                return TypedShape(parts[0], segments, rest, text)
            }
        }
    }

    /**
     * An `http:path:` pattern as a sequence of [Part]s, one per element of the compiled pattern. It is read from the
     * pattern's text and then checked against what [HttpPathPatternParser] built, element by element: if the two ever
     * disagree (the parser changed), the pattern is not analyzed rather than analyzed wrongly.
     */
    private class HttpShape(val parts: List<Part>) {
        val leadingRest: Boolean = parts.size > 1 && parts[0] is Part.Rest

        companion object {
            fun of(pattern: HttpPathPattern): HttpShape? {
                val parts = read(pattern.pattern)
                val elements = HttpPathPatternParser(pattern.pattern, pattern.caseSensitive).parse().elements
                if (parts.size != elements.size || parts.indices.any { !agrees(parts[it], elements[it]) }) {
                    return null
                }
                return HttpShape(parts)
            }

            private fun read(text: String): List<Part> {
                val parts = ArrayList<Part>()
                var begin = 0
                while (begin <= text.length) {
                    val end = text.indexOf(HttpPath.SEPARATOR, begin).let { if (it < 0) text.length else it }
                    if (end > begin) {
                        val part = segment(text.substring(begin, end))
                        if (part is Part.Rest && parts.lastOrNull() == Part.Separator) {
                            parts.removeAt(parts.size - 1)
                        }
                        parts += part
                    }
                    if (end == text.length) {
                        break
                    }
                    parts += Part.Separator
                    begin = end + 1
                }
                return parts
            }

            private fun segment(text: String): Part = when {
                text == "**" -> Part.Rest(null)
                text == "*" -> Part.Wildcard
                text.startsWith("{*") -> Part.Rest(text.substring(2, text.length - 1))
                text.startsWith('{') && text.indexOf('}') == text.length - 1 && text.lastIndexOf('{') == 0 ->
                    Part.Capture(text.substring(1, text.length - 1))

                text.any { it == '*' || it == '?' || it == '{' } -> Part.Other(text)
                else -> Part.Literal(text)
            }

            private fun agrees(part: Part, element: PathElement): Boolean = when (part) {
                Part.Separator -> element === PathElement.Separator
                Part.Wildcard -> element === PathElement.Wildcard
                is Part.Literal -> element is PathElement.Literal
                is Part.Capture -> element is PathElement.Capture
                is Part.Rest -> element is PathElement.Rest
                is Part.Other -> element is PathElement.SingleChar || element is PathElement.Glob
            }
        }
    }
}
