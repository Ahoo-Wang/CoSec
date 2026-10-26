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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class SnapshotDocumentCodecTest {
    private val publishedAt = Instant.parse("2026-10-21T08:00:00Z")
    private val body = """{"schema":1,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[{"id":"p"}]}"""

    @Test
    fun decodesTheHeaderAndKeepsTheWholeBody() {
        val document = SnapshotDocumentCodec.decode(body.encodeToByteArray())
        document.schema.assert().isEqualTo(SnapshotDocument.CURRENT_SCHEMA)
        document.revision.assert().isEqualTo(42L)
        document.publishedAt.assert().isEqualTo(publishedAt)
        document.bodyBytes().decodeToString().assert().isEqualTo(body)
    }

    @Test
    fun encodesTheBodyUnchanged() {
        val document = SnapshotDocument(1, 42, publishedAt, body.encodeToByteArray())
        SnapshotDocumentCodec.encode(document).decodeToString().assert().isEqualTo(body)
        SnapshotDocumentCodec.decode(SnapshotDocumentCodec.encode(document)).assert().isEqualTo(document)
    }

    @Test
    fun refusesToEncodeABodyWhoseHeaderDiffers() {
        assertThrows<IllegalArgumentException> {
            SnapshotDocumentCodec.encode(SnapshotDocument(1, 43, publishedAt, body.encodeToByteArray()))
        }
        assertThrows<IllegalArgumentException> {
            SnapshotDocumentCodec.encode(SnapshotDocument(1, 42, publishedAt.plusMillis(1), body.encodeToByteArray()))
        }
        // A body without its header, as the in-memory store's tests write it, would fail every read.
        assertThrows<SnapshotFormatException> {
            SnapshotDocumentCodec.encode(
                SnapshotDocument(1, 42, publishedAt, """{"policies":[]}""".encodeToByteArray())
            )
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // repeated header fields: the compiler rejects them, so must the codec (#1003)
            """{"schema":1,"revision":42,"revision":43,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":1,"schema":1,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":1,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","publishedAt":"2026-10-21T08:00:01Z"}""",
            // a repeated key anywhere in the body
            """{"schema":1,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[],"policies":[]}""",
            """{"schema":1,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[{"id":"a","id":"b"}]}""",
            // missing or mistyped header fields
            """{"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":1,"revision":42,"policies":[]}""",
            """{"schema":"1","revision":42,"publishedAt":"2026-10-21T08:00:00Z"}""",
            """{"schema":1,"revision":4.2,"publishedAt":"2026-10-21T08:00:00Z"}""",
            """{"schema":1,"revision":0,"publishedAt":"2026-10-21T08:00:00Z"}""",
            """{"schema":1,"revision":42,"publishedAt":"yesterday"}""",
            """{"schema":1,"revision":42,"publishedAt":1}""",
            """{"schema":99999999999,"revision":42,"publishedAt":"2026-10-21T08:00:00Z"}""",
            // a schema this version does not know
            """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            // not a document
            """[]""",
            """{"schema":1,"revision":42,"publishedAt":"2026-10-21T08:00:00Z"} {}""",
            """{"schema":1,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[""",
            """""",
        ]
    )
    fun rejectsWhatTheCompilerWouldReject(text: String) {
        assertThrows<SnapshotFormatException> { SnapshotDocumentCodec.decode(text.encodeToByteArray()) }
    }

    /** Round 2: only UTF-8 without a byte order mark, so stored entries keep meaningful byte offsets. */
    @Test
    fun rejectsBodiesThatAreNotUtf8() {
        val utf16 = body.toByteArray(Charsets.UTF_16BE)
        val utf16le = body.toByteArray(Charsets.UTF_16LE)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.encodeToByteArray()
        val utf16Bom = body.toByteArray(Charsets.UTF_16)
        val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16le
        val utf32 = body.toByteArray(charset("UTF-32BE"))
        listOf(
            utf16,
            utf16le,
            bom,
            utf16Bom,
            utf16LeBom,
            utf32,
            byteArrayOf(0),
            byteArrayOf(0xFE.toByte(), 0x7B)
        ).forEach { bytes ->
            assertThrows<SnapshotFormatException> { SnapshotDocumentCodec.decode(bytes) }
        }
        // invalid UTF-8 after the first bytes is the parser's to refuse
        val invalid = body.encodeToByteArray().copyOf().also { it[it.size - 10] = 0xC3.toByte() }
        assertThrows<SnapshotFormatException> { SnapshotDocumentCodec.decode(invalid) }
        // too short to carry a byte order mark: the parser decides
        assertThrows<SnapshotFormatException> { SnapshotDocumentCodec.decode("{".encodeToByteArray()) }
    }
}
