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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ProblemEncoderTest {
    private fun encode(type: ProblemType, requestId: String, traceId: String? = null): String =
        String(ProblemEncoder.encode(Problem(type, requestId, traceId)), Charsets.US_ASCII)

    /** The example of RFC 0006 §6.2, byte for byte. */
    @Test
    fun forbidden() {
        encode(ProblemType.FORBIDDEN, "0TEC7Hw00001zR0").assert().isEqualTo(
            """{"type":"https://cosec.ahoo.me/problems/forbidden","title":"Forbidden","status":403,""" +
                """"code":"FORBIDDEN","requestId":"0TEC7Hw00001zR0"}"""
        )
    }

    @Test
    fun traceIdComesLast() {
        encode(ProblemType.TOO_MANY_REQUESTS, "r", "t").assert().isEqualTo(
            """{"type":"https://cosec.ahoo.me/problems/too-many-requests","title":"Too Many Requests","status":429,""" +
                """"code":"TOO_MANY_REQUESTS","requestId":"r","traceId":"t"}"""
        )
    }

    @Test
    fun valuesAreEscaped() {
        val body = encode(ProblemType.UNAVAILABLE, "a\"b\\c\n\r\t\u0001\u007f é😀", "\",\"x\":\"y")
        body.assert().endsWith(
            """"requestId":"a\"b\\c\n\r\t\u0001\u007f \u00e9\ud83d\ude00","traceId":"\",\"x\":\"y"}"""
        )
    }

    @Test
    fun everyTypeHasItsOwnSlugTitleAndCode() {
        ProblemType.entries.map { it.slug }.toSet().assert().hasSize(ProblemType.entries.size)
        ProblemType.entries.map { it.title }.toSet().assert().hasSize(ProblemType.entries.size)
        ProblemType.entries.forEach {
            it.code.assert().isEqualTo(it.slug.uppercase().replace('-', '_'))
            it.uri.assert().isEqualTo("https://cosec.ahoo.me/problems/${it.slug}")
            Problem(it, "r", null).status.assert().isEqualTo(it.status)
        }
    }
}
