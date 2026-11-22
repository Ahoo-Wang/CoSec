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
 * - An encoded `;` (`%3B`) or an encoded control character (`%00` to `%1F`, `%7F`), in either case of the hex digits
 *   and anywhere in the path, path parameters included, is rejected (`PIP-30`). CoSec and Spring read them as text in
 *   a segment, but a backend that decodes before it strips path parameters serves `/admin%3Bx/users` as `/admin/users`,
 *   one in native code stops at NUL, and one that drops what follows a control character serves another path again: a
 *   gateway fronts backends of any kind. A double-encoded one (`%253B`, `%2500`) decodes to text and is kept. A raw
 *   control character is rejected too: the servers CoSec runs on refuse one before any filter, and the rule does not
 *   depend on which server hands the path over. A raw `;` is a path parameter, removed as above.
 * - A path longer than [MAX_PATH_LENGTH] characters as received, or with a segment longer than [MAX_SEGMENT_LENGTH]
 *   once decoded, after removing path parameters, is rejected (`PIP-5`). The path is counted as it arrives: that is
 *   what a request carries, and it bounds every length below. A segment is counted as the matchers see it, decoded, in
 *   UTF-16 units, so its bound does not depend on the script: a CJK character is nine characters encoded, and a
 *   segment of 29 of them, 261 encoded, is carried (#1853). The path's bound still applies to what arrives, so one
 *   segment of CJK text holds at most 227 of them. A pattern that matches the path as received
 *   (`decodeAndParseSegments: false`, an ALLOW only, `DOC-14`) sees the encoded segment, which is within the path's
 *   bound: matching a segment costs its glob's units times the value's length, so one long segment costs no more than
 *   the short ones of a path as long. The bounds keep one request from occupying an event loop. A path whose segments
 *   are all within the bound as received is never decoded for it, since decoding only shortens a segment.
 *
 * The engine's `HttpPath` does not resolve `.` or `..` itself: a wildcard under `/public` matches `/public/../admin`.
 * Rejecting dot segments here is what keeps such a path from reaching a public wildcard.
 *
 * Decoding is single: `%252e%252e` decodes to the literal `%2e%2e`, as it does in the matchers.
 */
@InternalCoSecApi
object PathNormalizer {
    /**
     * The longest path accepted, in characters as received, percent-encoded: it bounds what arrives, and so every segment
     * decoded too. Common proxies and servers limit the whole URI to 8 KB.
     */
    const val MAX_PATH_LENGTH = 2048

    /**
     * The longest segment of a request's path accepted, in UTF-16 units once decoded, after path parameters are removed:
     * room for an id, a slug or a file name, in any script. [withinBounds] counts a text's segments as written.
     */
    const val MAX_SEGMENT_LENGTH = 256

    private const val ENCODED_SEPARATOR = "%2F"
    private const val PATH = "path"
    private const val HEX = 16
    private const val SEMICOLON = 0x3B
    private const val FIRST_PRINTABLE = 0x20
    private const val DELETE = 0x7F

    /** The path to authorize, still encoded; null when [rawPath] cannot be interpreted without ambiguity. */
    fun normalize(rawPath: String): String? {
        if (rawPath.length > MAX_PATH_LENGTH || hazardAt(rawPath) >= 0) {
            return null
        }
        val path = withoutPathParameters(rawPath)
        return if (hasLongSegment(path, decoded = true) || isAmbiguous(path)) null else path
    }

    /**
     * Why [normalize] rejects [rawPath], for the outcome's reason: the kind of sequence and the segment it is in for an
     * encoded `;`, an encoded or a raw control character (`PIP-30`), `path` for every other rejection. It never repeats the path, so
     * nothing a client sent reaches a log through it. Called only for a path [normalize] rejected.
     */
    fun rejection(rawPath: String): String {
        val at = hazardAt(rawPath)
        if (at < 0) {
            return PATH
        }
        val kind = when {
            rawPath[at] != '%' -> "a control character"
            escaped(rawPath, at) == SEMICOLON -> "an encoded ';'"
            else -> "an encoded control character"
        }
        return "$PATH: $kind in segment ${segmentOf(rawPath, at)}"
    }

    /**
     * The index in [path] of the first control character, or of the first `%` that encodes `;` or a control character;
     * -1 when there is none. One pass, with no allocation.
     */
    private fun hazardAt(path: String): Int {
        for (index in path.indices) {
            val char = path[index]
            if (isControl(char.code) || (char == '%' && isHazard(escaped(path, index)))) {
                return index
            }
        }
        return -1
    }

    private fun isHazard(value: Int): Boolean = value == SEMICOLON || isControl(value)

    private fun isControl(value: Int): Boolean = value in 0 until FIRST_PRINTABLE || value == DELETE

    /** The byte the escape at [index] of [path] encodes, in either case of its hex digits; -1 when it is malformed. */
    private fun escaped(path: String, index: Int): Int {
        if (index + 2 >= path.length) {
            return -1
        }
        val high = Character.digit(path[index + 1], HEX)
        val low = Character.digit(path[index + 2], HEX)
        return if (high < 0 || low < 0) -1 else high * HEX + low
    }

    /** The number, from 1, of the segment of [path] that holds [index]. */
    private fun segmentOf(path: String, index: Int): Int {
        var separators = 0
        for (at in 0 until index) {
            if (path[at] == '/') {
                separators++
            }
        }
        return if (path.startsWith('/')) separators else separators + 1
    }

    /**
     * Whether [text] is within the bounds a request's path is held to, [MAX_PATH_LENGTH] in all and [MAX_SEGMENT_LENGTH]
     * per segment, counted as written, path parameters included: for what the control plane decides without a request
     * (review L5 of #1200, RC1-41), a simulated, explained or analyzed typed id, whose glob segments would otherwise
     * cost the evaluation more than any request can. As written, not decoded as a request's segment is: a typed id is
     * matched as written, so what is written is what the matchers see. (An `http` path is read by [normalize].)
     */
    @InternalCoSecApi
    fun withinBounds(text: String): Boolean = text.length <= MAX_PATH_LENGTH && !hasLongSegment(text, decoded = false)

    /**
     * Whether a segment of [path] is longer than [MAX_SEGMENT_LENGTH]: as written, or with [decoded] as the matchers see
     * it. Only a segment longer than the bound as written is decoded: decoding never lengthens one.
     */
    private fun hasLongSegment(path: String, decoded: Boolean): Boolean {
        if (path.length <= MAX_SEGMENT_LENGTH) {
            return false
        }
        var start = 0
        while (start <= path.length) {
            val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
            if (end - start > MAX_SEGMENT_LENGTH && (!decoded || decodedLength(path, start, end) > MAX_SEGMENT_LENGTH)) {
                return true
            }
            start = end + 1
        }
        return false
    }

    /** The length of [path]'s segment from [start] to [end] decoded, in UTF-16 units; unbounded when it cannot be decoded. */
    private fun decodedLength(path: String, start: Int, end: Int): Int =
        PercentDecoder.decode(path.substring(start, end), plusIsSpace = false)?.length ?: Int.MAX_VALUE

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
