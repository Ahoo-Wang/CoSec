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
@InternalCoSecApi
sealed class ResourcePattern {
    /** The names of the captured variables, in the order [Captures] holds them. */
    abstract val variableNames: List<String>

    abstract fun match(target: ResourceTarget): Captures?

    /** Whether the resource matches, without capturing: allocates nothing beyond parsing the path once. */
    abstract fun matches(target: ResourceTarget): Boolean

    /** [match], an evaluation's: glob segments use the table of [scratch]. */
    internal open fun match(target: ResourceTarget, scratch: ScratchSource): Captures? = match(target)

    /** [matches], an evaluation's: glob segments use the table of [scratch]. */
    internal open fun matches(target: ResourceTarget, scratch: ScratchSource): Boolean = matches(target)

    /** This pattern as a DENY's or a rate limit rule's reads it (`PIP-35`): the same but for an `http:path:` pattern. */
    internal open fun inDenyDirection(): ResourcePattern = this

    /** Every resource; captures nothing. */
    @InternalCoSecApi
    object AnyResource : ResourcePattern() {
        override val variableNames: List<String> = emptyList()

        override fun match(target: ResourceTarget): Captures = Captures.NONE

        override fun matches(target: ResourceTarget): Boolean = true

        override fun toString(): String = "*"
    }

    @InternalCoSecApi
    class Http internal constructor(
        val path: HttpPathPattern,
        /**
         * Set on the patterns of DENY statements and rate limit rules ([inDenyDirection], `PIP-35`): [path]
         * without its trailing `/`, or [path] itself when it has none. A trailing `/`, of the request's path or of the
         * pattern, does not count against it, since a backend may serve both spellings as one.
         */
        private val loose: HttpPathPattern? = null,
    ) : ResourcePattern() {
        override val variableNames: List<String> get() = path.variableNames

        /** The literal start the index files it by: [loose]'s, which is a prefix of [path]'s own (`IDX-1`). */
        internal val indexPrefix: Array<String?> get() = (loose ?: path).prefix

        /** A trailing separator does not count (`PIP-35`); an ALLOW's and the boundary's patterns stay as written. */
        override fun inDenyDirection(): Http = if (loose != null) this else Http(path, looseOf(path))

        /**
         * The path [loose] matches when [path] did not: the request's path without its trailing `/`, or the path as it
         * is when only the pattern had one. Null when neither had one: then [loose] is [path], which did not match.
         */
        private fun loosePath(target: ResourceTarget): HttpPath? {
            loose ?: return null
            return target.withoutTrailingSeparator(path.decodeAndParseSegments)
                ?: target.httpPath(path.decodeAndParseSegments).takeIf { loose !== path }
        }

        override fun match(target: ResourceTarget): Captures? {
            if (target.resource.type != HTTP_TYPE) {
                return null
            }
            return path.match(target.httpPath(path.decodeAndParseSegments))
                ?: loosePath(target)?.let { loose?.match(it) }
        }

        override fun matches(target: ResourceTarget): Boolean =
            target.resource.type == HTTP_TYPE && (
                path.matches(target.httpPath(path.decodeAndParseSegments)) ||
                    loosePath(target)?.let { loose?.matches(it) } == true
                )

        // Only glob segments use the table: a pattern without one takes the plain path, and never makes it.
        override fun match(target: ResourceTarget, scratch: ScratchSource): Captures? {
            if (path.globUnits == 0) {
                return match(target)
            }
            if (target.resource.type != HTTP_TYPE) {
                return null
            }
            return path.match(target.httpPath(path.decodeAndParseSegments), scratch.scratch())
                ?: loosePath(target)?.let { loose?.match(it, scratch.scratch()) }
        }

        override fun matches(target: ResourceTarget, scratch: ScratchSource): Boolean {
            if (path.globUnits == 0) {
                return matches(target)
            }
            return target.resource.type == HTTP_TYPE && (
                path.matches(target.httpPath(path.decodeAndParseSegments), scratch.scratch()) ||
                    loosePath(target)?.let { loose?.matches(it, scratch.scratch()) } == true
                )
        }

        override fun equals(other: Any?): Boolean = other is Http && other.path == path

        override fun hashCode(): Int = path.hashCode()

        override fun toString(): String = HTTP_PREFIX + path.pattern
    }

    @InternalCoSecApi
    class Typed internal constructor(
        val type: String,
        private val segments: Array<TypedSegment>,
        /** Whether the last segment is `**`. */
        private val rest: Boolean,
        override val variableNames: List<String>,
        private val text: String,
    ) : ResourcePattern() {
        private val names = variableNames.toTypedArray()

        /** A type alone, as `order`: it matches only the resource of that type with an empty id. */
        val bare: Boolean get() = segments.isEmpty() && !rest

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

    @InternalCoSecApi
    companion object {
        /** The resource type of HTTP requests. */
        const val HTTP_TYPE: String = "http"
        const val HTTP_PREFIX: String = "http:path:"

        /**
         * The longest resource pattern, in characters (code points): `http:path:` and the longest path pattern,
         * [HttpPathPattern.MAX_LENGTH], which no request path exceeds; a typed pattern, which matches ids built from such
         * paths, is held to the same length (RC1-8, `DOC-4`).
         */
        const val MAX_LENGTH: Int = HTTP_PREFIX.length + HttpPathPattern.MAX_LENGTH

        /** The longest a resource pattern is written: each of its characters escaped (`DOC-15`). */
        const val MAX_WRITTEN_LENGTH: Int = HTTP_PREFIX.length + 2 * HttpPathPattern.MAX_LENGTH

        /**
         * [pattern] without its trailing separator (`PIP-35`), or [pattern] itself: the root and a pattern without one
         * keep theirs. A pattern that ends with a double star or a `{*name}` has none, and matches either spelling.
         */
        private fun looseOf(pattern: HttpPathPattern): HttpPathPattern {
            val text = pattern.pattern
            if (text.length <= 1 || !text.endsWith('/')) {
                return pattern
            }
            return HttpPathPattern.compile(text.dropLast(1), pattern.caseSensitive, pattern.decodeAndParseSegments)
        }

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
            // The characters it stands for: an escape is one (`DOC-15`).
            val escapes = pattern.length - PatternEscapes.unescapedLength(pattern)
            val length = pattern.codePointCount(0, pattern.length) - escapes
            if (length > MAX_LENGTH) {
                throw InvalidPatternException(
                    pattern,
                    MAX_LENGTH,
                    "the pattern is $length characters, more than $MAX_LENGTH"
                )
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
@InternalCoSecApi
class ResourceTarget(val resource: Resource) {
    private var decoded: HttpPath? = null
    private var raw: HttpPath? = null
    private var decodedLoose: HttpPath? = null
    private var rawLoose: HttpPath? = null

    /**
     * The path of an `http` resource without its trailing `/` (`PIP-35`), or null when it has none or is the root.
     * Parsed once, and only for a path that ends with `/`.
     */
    internal fun withoutTrailingSeparator(decodeAndParseSegments: Boolean): HttpPath? {
        val id = resource.id
        if (id.length <= 1 || id[id.length - 1] != '/') {
            return null
        }
        return if (decodeAndParseSegments) {
            decodedLoose ?: HttpPath.parse(id.substring(0, id.length - 1), true).also { decodedLoose = it }
        } else {
            rawLoose ?: HttpPath.parse(id.substring(0, id.length - 1), false).also { rawLoose = it }
        }
    }

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
        val plainType = literal(type, 0, "type")
        var at = type.length + 1
        val segments = ArrayList<TypedSegment>()
        var rest = false
        for (index in 1 until parts.size) {
            val part = parts[index]
            when {
                part == "**" && index == parts.size - 1 -> rest = true
                part == "*" -> segments.add(TypedSegment.Wildcard)
                part.startsWith('{') && part.endsWith('}') -> segments.add(TypedSegment.Capture(variable(part, at)))
                else -> segments.add(TypedSegment.Literal(literal(part, at, "segment")))
            }
            at += part.length + 1
        }
        return ResourcePattern.Typed(plainType, segments.toTypedArray(), rest, variableNames.toList(), pattern)
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

    /**
     * [text], the type or a literal segment at [at], with its escapes resolved (`DOC-15`): `*`, `?`, `{` and `}` are
     * written escaped (a lone `*` is a wildcard and `**` is only allowed last), the reserved characters too, and a type
     * holds no `:`.
     */
    private fun literal(text: String, at: Int, what: String): String {
        if (text.isEmpty()) {
            throw invalid(at, "the $what is empty")
        }
        return PatternEscapes.literal(pattern, text, at, "*?{}") { char ->
            when {
                char.isWhitespace() || char.isISOControl() -> "'$char' is not allowed in a $what"
                char == ':' && what == "type" -> "':' is not allowed in a type"
                else -> null
            }
        }
    }

    private fun invalid(index: Int, reason: String) = InvalidPatternException(pattern, index, reason)
}
