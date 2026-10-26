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

import java.io.ByteArrayOutputStream

/**
 * The path every request is authorized on (RFC 0006 §5.3.1). The rules of `RequestPaths` in `cosec-core`, unchanged,
 * without Spring's `UriUtils`; `RequestPaths` goes with the 7.x pipeline at the switch (RFC 0008 §4.4).
 *
 * - `;` path parameters are removed from every segment: Spring's routing and servlet containers ignore them, so
 *   `/admin;x` reaches `/admin` and `/public/..;/admin` resolves to `/admin`.
 * - The result is decoded once, and a path with a `.` or `..` segment after decoding is rejected: matchers decode
 *   segments, so an encoded `..` would otherwise be authorized under a public wildcard and resolved by the container.
 * - A malformed percent-escape is rejected.
 *
 * Decoding is single: `%252e%252e` decodes to the literal `%2e%2e`, as it does in the matchers.
 */
object PathNormalizer {
    /** The path to authorize, still encoded; null when [rawPath] cannot be interpreted without ambiguity. */
    fun normalize(rawPath: String): String? {
        val path = withoutPathParameters(rawPath)
        return if (isTraversal(path)) null else path
    }

    private fun withoutPathParameters(path: String): String {
        if (';' !in path) {
            return path
        }
        return path.split('/').joinToString("/") { it.substringBefore(';') }
    }

    private fun isTraversal(path: String): Boolean {
        if ('%' !in path && '.' !in path) {
            return false
        }
        // fail-closed: a malformed percent-escape is never a traversal-free path.
        val decoded = PercentDecoder.decode(path, plusIsSpace = false) ?: return true
        return decoded.split('/').any { it == "." || it == ".." }
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
