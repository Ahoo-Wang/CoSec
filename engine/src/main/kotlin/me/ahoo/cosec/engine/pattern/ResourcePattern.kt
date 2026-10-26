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

import me.ahoo.cosec.model.Resource

/**
 * A resource pattern (RFC 0005 §5.1, §5.2). Matching returns the variables the pattern captured, which only the
 * statement that holds the pattern sees (RFC 0001 P6), or `null` when the resource does not match.
 *
 * - `http:path:<pattern>`: an [HttpPathPattern] over the path of an `http` resource.
 * - `type`, `type/segment/...`: a typed resource. A segment is a literal, `*` (one non-empty segment), `{name}` (one
 *   non-empty segment, captured) or, last, `**` (any number of segments). `order` matches the resource of type `order`
 *   with an empty id, and `order` followed by `**` every `order`. Ids are compared as written, split on `/`, never decoded.
 * - [AnyResource], for a statement without a resource.
 */
sealed class ResourcePattern {
    /** The names of the captured variables, in the order [Captures] holds them. */
    abstract val variableNames: List<String>

    abstract fun match(target: ResourceTarget): Captures?

    /** Whether the resource matches, without capturing: allocates nothing beyond parsing the path once. */
    abstract fun matches(target: ResourceTarget): Boolean

    /** Every resource; captures nothing. */
    object AnyResource : ResourcePattern() {
        override val variableNames: List<String> = emptyList()

        override fun match(target: ResourceTarget): Captures = Captures.NONE

        override fun matches(target: ResourceTarget): Boolean = true

        override fun toString(): String = "*"
    }

    class Http internal constructor(val path: HttpPathPattern) : ResourcePattern() {
        override val variableNames: List<String> get() = path.variableNames

        override fun match(target: ResourceTarget): Captures? {
            if (target.resource.type != HTTP_TYPE) {
                return null
            }
            return path.match(target.httpPath(path.decodeAndParseSegments))
        }

        override fun matches(target: ResourceTarget): Boolean =
            target.resource.type == HTTP_TYPE && path.matches(target.httpPath(path.decodeAndParseSegments))

        override fun equals(other: Any?): Boolean = other is Http && other.path == path

        override fun hashCode(): Int = path.hashCode()

        override fun toString(): String = HTTP_PREFIX + path.pattern
    }

    class Typed internal constructor(
        val type: String,
        private val segments: Array<TypedSegment>,
        /** Whether the last segment is `**`. */
        private val rest: Boolean,
        override val variableNames: List<String>,
        private val text: String,
    ) : ResourcePattern() {
        private val names = variableNames.toTypedArray()

        override fun match(target: ResourceTarget): Captures? {
            val resource = target.resource
            if (resource.type != type) {
                return null
            }
            val values = if (names.isEmpty()) null else arrayOfNulls<String>(names.size)
            if (!matchId(resource.id, values)) {
                return null
            }
            return if (values == null) Captures.NONE else Captures(names, values.requireNoNulls())
        }

        override fun matches(target: ResourceTarget): Boolean {
            val resource = target.resource
            return resource.type == type && matchId(resource.id, null)
        }

        private fun matchId(id: String, values: Array<String?>?): Boolean {
            val fixed = segments.size
            var begin = 0
            for (index in 0 until fixed) {
                if (begin > id.length) {
                    return false
                }
                val end = id.indexOf(ID_SEPARATOR, begin).let { if (it < 0) id.length else it }
                if (!segments[index].matches(id, begin, end, values)) {
                    return false
                }
                begin = end + 1
            }
            // With `**` the id may go on; without it, it must end here. An empty id has no segments at all.
            return rest || (if (fixed == 0) id.isEmpty() else begin == id.length + 1)
        }

        override fun equals(other: Any?): Boolean = other is Typed && other.text == text

        override fun hashCode(): Int = text.hashCode()

        override fun toString(): String = text
    }

    companion object {
        /** The resource type of HTTP requests. */
        const val HTTP_TYPE: String = "http"
        const val HTTP_PREFIX: String = "http:path:"
        private const val ID_SEPARATOR = '/'

        /**
         * Parses [pattern], or throws [InvalidPatternException]. [caseSensitive] and [decodeAndParseSegments] are the
         * options of `http:path:` patterns; a typed pattern takes neither.
         */
        fun parse(
            pattern: String,
            caseSensitive: Boolean = true,
            decodeAndParseSegments: Boolean = true,
        ): ResourcePattern {
            if (pattern.startsWith(HTTP_PREFIX)) {
                val path = pattern.substring(HTTP_PREFIX.length)
                return Http(HttpPathPattern.compile(path, caseSensitive, decodeAndParseSegments))
            }
            if (!caseSensitive || !decodeAndParseSegments) {
                throw InvalidPatternException(pattern, 0, "only http:path: patterns take path options")
            }
            return TypedPatternParser(pattern).parse()
        }
    }
}

/**
 * The resource of one request, with its path parsed at most once per option for every [ResourcePattern.Http]. One
 * evaluation owns it; it is not shared between threads.
 */
class ResourceTarget(val resource: Resource) {
    private var decoded: HttpPath? = null
    private var raw: HttpPath? = null

    /** The path of an `http` resource. Throws [IllegalArgumentException] for a malformed percent-escape. */
    fun httpPath(decodeAndParseSegments: Boolean): HttpPath =
        if (decodeAndParseSegments) {
            decoded ?: HttpPath.parse(resource.id, true).also { decoded = it }
        } else {
            raw ?: HttpPath.parse(resource.id, false).also { raw = it }
        }
}

/** One segment of a [ResourcePattern.Typed] other than a final `**`. */
internal sealed class TypedSegment {
    abstract fun matches(id: String, begin: Int, end: Int, values: Array<String?>?): Boolean

    class Literal(private val text: String) : TypedSegment() {
        override fun matches(id: String, begin: Int, end: Int, values: Array<String?>?): Boolean =
            end - begin == text.length && id.regionMatches(begin, text, 0, text.length)
    }

    object Wildcard : TypedSegment() {
        override fun matches(id: String, begin: Int, end: Int, values: Array<String?>?): Boolean = end > begin
    }

    class Capture(private val variable: Int) : TypedSegment() {
        override fun matches(id: String, begin: Int, end: Int, values: Array<String?>?): Boolean {
            if (end == begin) {
                return false
            }
            if (values != null) {
                values[variable] = id.substring(begin, end)
            }
            return true
        }
    }
}

private class TypedPatternParser(private val pattern: String) {
    private val variableNames = ArrayList<String>()

    fun parse(): ResourcePattern.Typed {
        val parts = pattern.split('/')
        val type = parts[0]
        if (type == ResourcePattern.HTTP_TYPE) {
            throw invalid(0, "http resources are matched with ${ResourcePattern.HTTP_PREFIX}<pattern>")
        }
        requireLiteral(type, 0, "type")
        if (':' in type) {
            throw invalid(type.indexOf(':'), "':' is not allowed in a type")
        }
        var at = type.length + 1
        val segments = ArrayList<TypedSegment>()
        var rest = false
        for (index in 1 until parts.size) {
            val part = parts[index]
            when {
                part == "**" && index == parts.size - 1 -> rest = true
                part == "*" -> segments.add(TypedSegment.Wildcard)
                part.startsWith('{') && part.endsWith('}') -> segments.add(TypedSegment.Capture(variable(part, at)))
                else -> {
                    requireLiteral(part, at, "segment")
                    segments.add(TypedSegment.Literal(part))
                }
            }
            at += part.length + 1
        }
        return ResourcePattern.Typed(type, segments.toTypedArray(), rest, variableNames.toList(), pattern)
    }

    private fun variable(part: String, at: Int): Int {
        val name = part.substring(1, part.length - 1)
        VariableNames.invalid(name)?.let { throw invalid(at, it) }
        if (name in variableNames) {
            throw invalid(at, "variable [$name] is captured twice")
        }
        variableNames.add(name)
        return variableNames.size - 1
    }

    private fun requireLiteral(text: String, at: Int, what: String) {
        if (text.isEmpty()) {
            throw invalid(at, "the $what is empty")
        }
        val illegal = text.indexOfFirst { it in "*?{}" || it.isWhitespace() || it.isISOControl() }
        if (illegal >= 0) {
            throw invalid(at + illegal, "'${text[illegal]}' is not allowed in a $what; ** is only allowed last")
        }
    }

    private fun invalid(index: Int, reason: String) = InvalidPatternException(pattern, index, reason)
}
