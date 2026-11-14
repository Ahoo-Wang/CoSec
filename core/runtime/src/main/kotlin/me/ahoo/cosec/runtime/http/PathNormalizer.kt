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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Resource
import java.io.ByteArrayOutputStream

/**
 * The path every request is authorized on (RFC 0006 §5.3.1), by the rules below and without Spring's `UriUtils`.
 * Every rejection is 400 `invalid-request`.
 *
 * - `;` path parameters are removed from every segment: Spring's routing and servlet containers ignore them, so
 *   `/admin;x` reaches `/admin` and `/public/..;/admin` resolves to `/admin`.
 * - The result is decoded once, and a path with a `.` or `..` segment after decoding is rejected: matchers decode
 *   segments, so an encoded `..` would otherwise be authorized under a public wildcard and resolved by the container.
 * - A malformed percent-escape is rejected.
 * - A decoded segment is also a dot segment when its part before `;` is, as `..%3Bx` decodes to `..;x`.
 * - An empty segment before the end of the path (`//`, or what removing a path parameter leaves, as in `/;x/a`), an
 *   encoded separator (`%2F`, in either case) and a `\` (also `%5C`, in either case) are rejected (`PIP-14`). A pattern
 *   reads each of them as part of a segment, or as a segment of its own, so a DENY on the segments under `/admin` does
 *   not match `//admin/1`, `/admin%2F1` or `/admin\1`, which a backend that merges slashes, decodes separators or reads
 *   `\` as `/` (as some containers and every browser do) serves as `/admin/1`. A trailing `/` is kept: it is one more
 *   thing a pattern says or does not, and no merging turns it into another path. A double-encoded separator (`%252F`,
 *   `%255C`) decodes to text and is kept, as `%252e` is.
 * - A path longer than [MAX_PATH_LENGTH] characters, or with a segment longer than [MAX_SEGMENT_LENGTH] after removing
 *   path parameters, is rejected (both counted encoded, which bounds the decoded length). Wildcard matching in the
 *   engine costs about the square of a segment's length: an 8 KB segment takes hundreds of milliseconds per statement,
 *   so the bound keeps one request from occupying an event loop.
 *
 * The engine's `HttpPath` does not resolve `.` or `..` itself: a wildcard under `/public` matches `/public/../admin`.
 * Rejecting dot segments here is what keeps such a path from reaching a public wildcard.
 *
 * Decoding is single: `%252e%252e` decodes to the literal `%2e%2e`, as it does in the matchers.
 */
object PathNormalizer {
    /** The longest path accepted, in characters as received. Common proxies and servers limit the whole URI to 8 KB. */
    const val MAX_PATH_LENGTH = 2048

    /** The longest segment accepted, in characters as received: room for an id, a slug or a file name. */
    const val MAX_SEGMENT_LENGTH = 256

    private const val ENCODED_SEPARATOR = "%2F"

    /** The path to authorize, still encoded; null when [rawPath] cannot be interpreted without ambiguity. */
    fun normalize(rawPath: String): String? {
        if (rawPath.length > MAX_PATH_LENGTH) {
            return null
        }
        val path = withoutPathParameters(rawPath)
        return if (hasLongSegment(path) || isAmbiguous(path)) null else path
    }

    /**
     * Whether [text] is within the bounds a request's path is held to, [MAX_PATH_LENGTH] in all and [MAX_SEGMENT_LENGTH]
     * per segment, counted as written, path parameters included: for what the control plane decides without a request
     * (review L5 of #1200, RC1-41), a simulated, explained or analyzed resource, whose glob segments would otherwise
     * cost the evaluation more than any request can.
     */
    @InternalCoSecApi
    fun withinBounds(text: String): Boolean = text.length <= MAX_PATH_LENGTH && !hasLongSegment(text)

    /**
     * [resource] as enforcement reads it, or null when enforcement would refuse it: the one judgement behind what a check
     * by host code decides (`AuthorizationChecker`, `PIP-15`) and what the control plane simulates or explains (`CTL-3`),
     * so that neither decides what no request could be decided on. An `http` path goes through [normalize], since the
     * engine does not resolve a dot segment and a wildcard under `/public` would match `/public/../admin`; a typed id,
     * which no request carries as written, is held to [withinBounds], since its globs would otherwise cost more than any
     * request's.
     */
    @InternalCoSecApi
    fun enforceable(resource: Resource): Resource? {
        val id = if (resource.type == ResourcePattern.HTTP_TYPE) {
            normalize(resource.id)
        } else {
            resource.id.takeIf(::withinBounds)
        }
        return when (id) {
            null -> null
            resource.id -> resource
            else -> Resource(resource.type, id, resource.attributes)
        }
    }

    private fun hasLongSegment(path: String): Boolean {
        if (path.length <= MAX_SEGMENT_LENGTH) {
            return false
        }
        var start = 0
        while (start <= path.length) {
            val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
            if (end - start > MAX_SEGMENT_LENGTH) {
                return true
            }
            start = end + 1
        }
        return false
    }

    private fun withoutPathParameters(path: String): String {
        if (';' !in path) {
            return path
        }
        return path.split('/').joinToString("/") { it.substringBefore(';') }
    }

    private fun isAmbiguous(path: String): Boolean {
        if (isPlain(path)) {
            return false
        }
        if (path.contains(ENCODED_SEPARATOR, ignoreCase = true)) {
            return true
        }
        // fail-closed: a malformed percent-escape is never a traversal-free path.
        val decoded = PercentDecoder.decode(path, plusIsSpace = false) ?: return true
        if ('\\' in decoded) {
            return true
        }
        val segments = decoded.split('/')
        for (index in segments.indices) {
            // An encoded `;` (`..%3Bx`) is a dot segment to whatever decodes before it strips path parameters.
            val segment = segments[index].substringBefore(';')
            // The first is what comes before the leading separator, the last what comes after a trailing one.
            if (isDotSegment(segment) || (segment.isEmpty() && index in 1 until segments.lastIndex)) {
                return true
            }
        }
        return false
    }

    private fun isDotSegment(segment: String): Boolean = segment == "." || segment == ".."

    /** Whether [path] has nothing [isAmbiguous] looks at: what most requests' paths are, in one pass with no allocation. */
    private fun isPlain(path: String): Boolean {
        for (index in path.indices) {
            when (path[index]) {
                '%', '.', '\\' -> return false
                '/' -> if (index > 0 && path[index - 1] == '/') return false
                else -> Unit
            }
        }
        return true
    }
}

/** Percent-decoding as UTF-8 (RFC 3986 §2.1). */
internal object PercentDecoder {
    private const val RADIX = 16
    private const val DECIMAL = 10

    /** [encoded] decoded, or null when it holds a `%` not followed by two hex digits. */
    fun decode(encoded: String, plusIsSpace: Boolean): String? {
        if ('%' !in encoded && !(plusIsSpace && '+' in encoded)) {
            return encoded
        }
        val decoded = StringBuilder(encoded.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < encoded.length) {
            val char = encoded[i]
            if (char == '%') {
                val high = hex(encoded.getOrNull(i + 1)) ?: return null
                val low = hex(encoded.getOrNull(i + 2)) ?: return null
                bytes.write(high * RADIX + low)
                i += 3
                continue
            }
            flush(bytes, decoded)
            decoded.append(if (plusIsSpace && char == '+') ' ' else char)
            i++
        }
        flush(bytes, decoded)
        return decoded.toString()
    }

    /** ASCII hex digits only: `Char.digitToInt` also takes other scripts' digits, which no host decodes. */
    private fun hex(char: Char?): Int? = when {
        char == null -> null
        char in '0'..'9' -> char - '0'
        char in 'a'..'f' -> char - 'a' + DECIMAL
        char in 'A'..'F' -> char - 'A' + DECIMAL
        else -> null
    }

    private fun flush(bytes: ByteArrayOutputStream, decoded: StringBuilder) {
        if (bytes.size() > 0) {
            decoded.append(bytes.toString(Charsets.UTF_8))
            bytes.reset()
        }
    }
}
