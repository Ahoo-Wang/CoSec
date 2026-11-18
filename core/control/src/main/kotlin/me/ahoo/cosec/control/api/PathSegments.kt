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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.control.change.InvalidControlRequestException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val MALFORMED = "A path parameter is not percent-encoded UTF-8 text."
private const val HEX_RADIX = 16
private const val ESCAPE_LENGTH = 3

/**
 * The value of a path parameter (#1385): the text of one segment, percent-decoded once.
 *
 * [ControlHttp] splits the raw path into segments first and decodes a parameter afterwards, so an encoded `/` (`%2F`) is
 * part of a value and never a second segment, and a route's own words (`documents`, `revisions`, a `:verb`) are never
 * written in escapes. It is path decoding, not form decoding: `+` stays `+`. An escape that is not `%` and two hex
 * digits, or bytes that are not UTF-8, make the request invalid (400), never a value that happens to decode.
 */
internal fun unescaped(segment: String): String {
    if ('%' !in segment) {
        return segment
    }
    val bytes = ByteArrayOutputStream(segment.length)
    var index = 0
    while (index < segment.length) {
        if (segment[index] != '%') {
            // A character the client sent unescaped is its own UTF-8 bytes.
            val end = index + Character.charCount(segment.codePointAt(index))
            bytes.writeBytes(segment.substring(index, end).toByteArray(Charsets.UTF_8))
            index = end
            continue
        }
        bytes.write(hexDigit(segment, index + 1) * HEX_RADIX + hexDigit(segment, index + 2))
        index += ESCAPE_LENGTH
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (e: CharacterCodingException) {
        throw InvalidControlRequestException(MALFORMED, e)
    }
}

private fun hexDigit(text: String, index: Int): Int {
    val digit = text.getOrNull(index)?.let { Character.digit(it, HEX_RADIX) } ?: -1
    if (digit < 0) {
        throw InvalidControlRequestException(MALFORMED)
    }
    return digit
}
