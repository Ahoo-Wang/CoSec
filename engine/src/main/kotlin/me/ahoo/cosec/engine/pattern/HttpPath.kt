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
 * A request path split the way Spring's `PathContainer.parsePath` splits it (RFC 0005 §5.2), parsed once per request
 * and matched by every [HttpPathPattern].
 *
 * The path is a sequence of elements: a separator for every `/`, and a segment for every non-empty text between two of
 * them, so `/a//b/` is separator, `a`, separator, separator, `b`, separator. With [decodeAndParseSegments], a segment
 * is matched by its value without `;` parameters, percent-decoded as UTF-8; otherwise by its text as written, except
 * that `%2F` reads as `/`, as in Spring.
 */
class HttpPath private constructor(
    val value: String,
    val decodeAndParseSegments: Boolean,
    /** The value each segment is matched by, or `null` for a separator. */
    private val elements: Array<String?>,
) {
    internal val size: Int get() = elements.size

    internal fun isSeparator(index: Int): Boolean = elements[index] == null

    /** The segment at [index], or `null` for a separator. */
    internal fun segment(index: Int): String? = elements[index]

    /** The elements from [from] to [to] as one string, separators as `/`: what `{*name}` captures. */
    internal fun join(from: Int, to: Int): String {
        val joined = StringBuilder()
        for (index in from until to) {
            joined.append(elements[index] ?: SEPARATOR_TEXT)
        }
        return joined.toString()
    }

    override fun toString(): String = value

    companion object {
        const val SEPARATOR: Char = '/'
        private const val SEPARATOR_TEXT = "/"

        /** Spring decodes an encoded separator even when it decodes nothing else; only in upper case. */
        private const val ENCODED_SEPARATOR = "%2F"

        /**
         * Parses [path]. With [decodeAndParseSegments], a malformed percent-escape (`%zz`, or a `%` without two hex
         * digits) throws [IllegalArgumentException], as it does in Spring: such a path has no defined meaning, and the
         * pipeline rejects it before authorization (`RequestPaths`).
         */
        fun parse(path: String, decodeAndParseSegments: Boolean = true): HttpPath {
            // Sized exactly up front: parsing runs once per request, and growing a list dominated it.
            val elements = arrayOfNulls<String>(countElements(path))
            var count = 0
            var begin = 0
            while (begin < path.length) {
                val end = path.indexOf(SEPARATOR, begin).let { if (it < 0) path.length else it }
                if (end > begin) {
                    elements[count++] = segment(path, begin, end, decodeAndParseSegments)
                }
                if (end == path.length) {
                    break
                }
                // A separator stays null.
                count++
                begin = end + 1
            }
            return HttpPath(path, decodeAndParseSegments, elements)
        }

        private fun needsWork(path: String, begin: Int, end: Int, decodeAndParseSegments: Boolean): Boolean {
            for (index in begin until end) {
                val char = path[index]
                if (char == PercentDecoding.ESCAPE || (decodeAndParseSegments && char == PercentDecoding.PARAMETER)) {
                    return true
                }
            }
            return false
        }

        /** One element per `/`, and one per run of other characters. */
        private fun countElements(path: String): Int {
            var count = 0
            for (index in path.indices) {
                if (path[index] == SEPARATOR || index == 0 || path[index - 1] == SEPARATOR) {
                    count++
                }
            }
            return count
        }

        private fun segment(path: String, begin: Int, end: Int, decodeAndParseSegments: Boolean): String {
            val segment = path.substring(begin, end)
            if (!needsWork(path, begin, end, decodeAndParseSegments)) {
                return segment
            }
            return if (decodeAndParseSegments) {
                PercentDecoding.valueToMatch(segment)
            } else {
                segment.replace(ENCODED_SEPARATOR, SEPARATOR_TEXT)
            }
        }
    }
}

/** Spring's `StringUtils.uriDecode` and the parameter parsing of `PathContainer`, for the values only. */
internal object PercentDecoding {
    const val PARAMETER = ';'
    private const val NAME_VALUE = '='
    const val ESCAPE = '%'
    private const val HEX_RADIX = 16
    private const val NIBBLE_BITS = 4

    /**
     * The segment's value before its first `;`, decoded. Parameters are discarded, but are still decoded where Spring
     * decodes them, so a path Spring rejects is rejected here as well.
     */
    fun valueToMatch(segment: String): String {
        val parameters = segment.indexOf(PARAMETER)
        if (parameters < 0) {
            return decode(segment)
        }
        val value = decode(segment.substring(0, parameters))
        var begin = parameters + 1
        while (begin <= segment.length) {
            val end = segment.indexOf(PARAMETER, begin).let { if (it < 0) segment.length else it }
            checkParameter(segment.substring(begin, end))
            begin = end + 1
        }
        return value
    }

    /**
     * As Spring's `parsePathParamValues`: a blank parameter is skipped; otherwise its name is decoded, and its values
     * too if the decoded name is not blank.
     */
    private fun checkParameter(parameter: String) {
        if (isBlank(parameter)) {
            return
        }
        val nameEnd = parameter.indexOf(NAME_VALUE)
        if (nameEnd < 0) {
            decode(parameter)
            return
        }
        if (!isBlank(decode(parameter.substring(0, nameEnd)))) {
            // Spring decodes each comma-separated value; a comma is not a hex digit, so decoding them as one fails alike.
            decode(parameter.substring(nameEnd + 1))
        }
    }

    /** Spring's `!StringUtils.hasText`, which uses `Character.isWhitespace` (Kotlin's `isWhitespace` is wider). */
    private fun isBlank(text: String): Boolean = text.all { Character.isWhitespace(it) }

    /** Runs of `%XX` escapes are decoded together as UTF-8; malformed byte sequences become U+FFFD. */
    fun decode(source: String): String {
        val first = source.indexOf(ESCAPE)
        if (first < 0) {
            return source
        }
        val output = StringBuilder(source.length)
        output.append(source, 0, first)
        var bytes: ByteArray? = null
        var index = first
        while (index < source.length) {
            if (source[index] != ESCAPE) {
                output.append(source[index])
                index++
                continue
            }
            val buffer = bytes ?: ByteArray((source.length - index) / 3).also { bytes = it }
            var count = 0
            while (index + 2 < source.length && source[index] == ESCAPE) {
                buffer[count++] = ((hex(source, index + 1) shl NIBBLE_BITS) or hex(source, index + 2)).toByte()
                index += 3
            }
            require(index >= source.length || source[index] != ESCAPE) { "Incomplete trailing escape (%) pattern" }
            output.append(buffer.decodeToString(0, count))
        }
        return output.toString()
    }

    private fun hex(source: String, index: Int): Int {
        val char = source[index]
        val digit = when (char) {
            in '0'..'9', in 'a'..'f', in 'A'..'F' -> char.digitToInt(HEX_RADIX)
            else -> -1
        }
        require(digit >= 0) { "Invalid encoded sequence" }
        return digit
    }
}
