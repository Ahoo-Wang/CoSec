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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/** The one way to make a snapshot document (SNP-12), with the strict header reader it moved from `cosec-cocache`. */
class SnapshotDocumentsTest {
    private val publishedAt = Instant.parse("2026-10-21T08:00:00Z")
    private val body = """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[{"id":"p"}]}"""

    @Test
    fun decodesTheHeaderAndKeepsTheWholeBody() {
        val document = SnapshotDocuments.read(body.encodeToByteArray())
        document.schema.assert().isEqualTo(SnapshotDocument.DOCUMENT_SCHEMA)
        document.revision.assert().isEqualTo(42L)
        document.publishedAt.assert().isEqualTo(publishedAt)
        document.bodyBytes().decodeToString().assert().isEqualTo(body)
    }

    /**
     * SNP-12: the header is the body's. A document read back from its own bytes is the same document, and there is no
     * other public way to make one, so no header can contradict its body.
     */
    @Test
    fun theHeaderIsTheBodys() {
        val document = SnapshotDocuments.read(body.encodeToByteArray())
        SnapshotDocuments.read(document.bodyBytes()).assert().isEqualTo(document)
    }

    /** A document the control plane encodes states its own header: the revision and time it is published at. */
    @Test
    fun aPolicyDocumentStatesItsOwnHeader() {
        val document = PolicyDocument(DocumentKind.TENANT, "acme", 42, publishedAt, emptyList(), emptyList())
        val stored = SnapshotDocuments.of(document)
        stored.revision.assert().isEqualTo(42L)
        stored.publishedAt.assert().isEqualTo(publishedAt)
        SnapshotDocuments.read(stored.bodyBytes()).assert().isEqualTo(stored)
    }

    /** RFC 0004's schema 2 is the one format; RFC 0003's schema 1 is no longer read. */
    @Test
    fun decodesOnlyTheDocumentFormat() {
        val platform = """{"schema":2,"revision":7,"publishedAt":"2026-10-21T08:00:00Z","policies":[],"bindings":[]}"""
        SnapshotDocuments.read(platform.encodeToByteArray()).schema.assert().isEqualTo(2)
        assertThrows<SnapshotFormatException> {
            SnapshotDocuments.read(
                """{"schema":1,"revision":7,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""".encodeToByteArray()
            )
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // repeated header fields: the compiler rejects them, so must the codec (#1003)
            """{"schema":2,"revision":42,"revision":43,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":2,"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","publishedAt":"2026-10-21T08:00:01Z"}""",
            // a repeated key anywhere in the body
            """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[],"policies":[]}""",
            """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[{"id":"a","id":"b"}]}""",
            // missing or mistyped header fields
            """{"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":2,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":2,"revision":42,"policies":[]}""",
            """{"schema":"2","revision":42,"publishedAt":"2026-10-21T08:00:00Z"}""",
            """{"schema":2,"revision":4.2,"publishedAt":"2026-10-21T08:00:00Z"}""",
            """{"schema":2,"revision":0,"publishedAt":"2026-10-21T08:00:00Z"}""",
            """{"schema":2,"revision":42,"publishedAt":"yesterday"}""",
            """{"schema":2,"revision":42,"publishedAt":1}""",
            """{"schema":99999999999,"revision":42,"publishedAt":"2026-10-21T08:00:00Z"}""",
            // a schema this version does not know
            """{"schema":3,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            // not a document
            """[]""",
            """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z"} {}""",
            """{"schema":2,"revision":42,"publishedAt":"2026-10-21T08:00:00Z","policies":[""",
            """""",
        ]
    )
    fun rejectsWhatTheCompilerWouldReject(text: String) {
        assertThrows<SnapshotFormatException> { SnapshotDocuments.read(text.encodeToByteArray()) }
    }

    /**
     * RC1-15, round 4 of #1194's review: a stored field name never reaches a message raw. A repeated name is refused by
     * the strict parser first, whose message names nothing; the codec's own check, which quotes the name, escapes it.
     */
    @Test
    fun aRepeatedFieldNameIsNeverQuotedRaw() {
        val text = """{"schema":2,"a\u0001b":1,"a\u0001b":2,"revision":4,"publishedAt":"2026-10-21T08:00:00Z"}"""
        val error = assertThrows<SnapshotFormatException> { SnapshotDocuments.read(text.encodeToByteArray()) }
        error.message!!.any { it.isISOControl() }.assert().isFalse()
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
            assertThrows<SnapshotFormatException> { SnapshotDocuments.read(bytes) }
        }
        // invalid UTF-8 after the first bytes is the parser's to refuse
        val invalid = body.encodeToByteArray().copyOf().also { it[it.size - 10] = 0xC3.toByte() }
        assertThrows<SnapshotFormatException> { SnapshotDocuments.read(invalid) }
        // too short to carry a byte order mark: the parser decides
        assertThrows<SnapshotFormatException> { SnapshotDocuments.read("{".encodeToByteArray()) }
    }
}
