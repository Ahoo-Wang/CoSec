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

package me.ahoo.cosec.runtime

/**
 * The only serializer of [Problem] (RFC 0006 §6.2): hand-written, so the three adapters write the same bytes whatever
 * the host's JSON configuration, and nothing needs reflection or AOT hints. No Jackson, no Spring `ProblemDetail`.
 *
 * Members, always in this order: `type`, `title`, `status`, `code`, `requestId`, then `traceId` when there is one.
 * The request id may come from the client, so strings are escaped per RFC 8259: `"`, `\` and every control character,
 * and every non-ASCII character as `\uXXXX`. The body is ASCII, valid JSON, and a value cannot add a member.
 */
object ProblemEncoder {
    const val MEDIA_TYPE = "application/problem+json"

    private const val DEL = 0x7F
    private const val FIRST_PRINTABLE = 0x20
    private const val HEX = "0123456789abcdef"
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0xF

    /** Everything up to the request id's value, per type: constant, so built once. */
    private val heads: Map<ProblemType, String> = ProblemType.entries.associateWith {
        val head = StringBuilder()
        head.append("{\"type\":")
        appendString(head, it.uri)
        head.append(",\"title\":")
        appendString(head, it.title)
        head.append(",\"status\":").append(it.status)
        head.append(",\"code\":")
        appendString(head, it.code)
        head.append(",\"requestId\":")
        head.toString()
    }

    fun encode(problem: Problem): ByteArray {
        val body = StringBuilder(BODY_CAPACITY)
        body.append(heads.getValue(problem.type))
        appendString(body, problem.requestId)
        problem.traceId?.let {
            body.append(",\"traceId\":")
            appendString(body, it)
        }
        body.append('}')
        return body.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char.code < FIRST_PRINTABLE || char.code >= DEL -> appendUnicodeEscape(out, char)
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    /** `\uXXXX` of one UTF-16 unit, so a supplementary character becomes its surrogate pair. DEL is escaped too. */
    private fun appendUnicodeEscape(out: StringBuilder, char: Char) {
        out.append("\\u")
        for (shift in intArrayOf(3 * NIBBLE, 2 * NIBBLE, NIBBLE, 0)) {
            out.append(HEX[(char.code shr shift) and NIBBLE_MASK])
        }
    }

    private const val BODY_CAPACITY = 192
}
