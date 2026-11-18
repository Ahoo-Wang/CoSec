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
