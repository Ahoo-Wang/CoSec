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

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.ObjectReader
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Stores a [SnapshotDocument] as its body: the whole JSON document, header included (RFC 0003 §3.3).
 *
 * - [decode] reads only the header (`schema`, `revision`, `publishedAt`) with the streaming parser, no data binding
 *   (RFC 0003 §10), and keeps the bytes for the compiler. It still walks the whole document, so that a repeated key
 *   at any depth, trailing content or broken JSON fails here, as the compiler's strict parser would.
 * - [encode] writes the body unchanged, so equal documents have equal bytes and CoCache's digest is stable. It refuses
 *   a body whose header is not the document's: such a value would fail every read.
 *
 * A failure throws [SnapshotFormatException]; CoCache reports it as unreadable content (`ForeignFormatException`).
 */
object SnapshotDocumentCodec : Codec<SnapshotDocument> {
    const val SCHEMA = "schema"
    const val REVISION = "revision"
    const val PUBLISHED_AT = "publishedAt"

    private val reader: ObjectReader = CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    override fun encode(value: SnapshotDocument): ByteArray {
        val body = value.bodyBytes()
        val stored = readHeader(body)
        val expected = Header(value.schema, value.revision, value.publishedAt)
        require(stored == expected) { "The body's header [$stored] differs from the document's [$expected]." }
        return body
    }

    override fun decode(bytes: ByteArray): SnapshotDocument {
        val header = readHeader(bytes)
        return SnapshotDocument(header.schema, header.revision, header.publishedAt, bytes)
    }

    private data class Header(val schema: Int, val revision: Long, val publishedAt: Instant)

    /** The header fields as found, before they are checked. */
    private class Fields {
        var schema: Int? = null
        var revision: Long? = null
        var publishedAt: Instant? = null

        fun header(): Header {
            val schema = schema ?: malformed("[$SCHEMA] is missing")
            if (schema != SnapshotDocument.DOCUMENT_SCHEMA) {
                malformed("schema $schema is not supported")
            }
            val revision = revision ?: malformed("[$REVISION] is missing")
            if (revision <= 0) {
                malformed("[$REVISION] must be positive, was $revision")
            }
            return Header(schema, revision, publishedAt ?: malformed("[$PUBLISHED_AT] is missing"))
        }
    }

    /**
     * JSON documents are UTF-8 without a byte order mark (RFC 8259). Jackson would also detect UTF-16 and UTF-32 from
     * the first bytes; refusing them here keeps byte offsets meaningful for writers that splice stored entries.
     */
    private fun requireUtf8(bytes: ByteArray) {
        val head = bytes.take(UTF_DETECTION_BYTES)
        val bom = head.size >= 2 && (
            (head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte()) ||
                (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) ||
                (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte())
            )
        if (bom || head.any { it == 0.toByte() }) {
            malformed("the document is not UTF-8 without a byte order mark")
        }
    }

    private const val UTF_DETECTION_BYTES = 4

    private fun readHeader(bytes: ByteArray): Header {
        requireUtf8(bytes)
        val found = Fields()
        try {
            reader.createParser(bytes).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    malformed("the document is not a JSON object")
                }
                val names = HashSet<String>()
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    val name = parser.currentName()
                    // The strict parser rejects it too; this check does not depend on a parser feature.
                    if (!names.add(name)) {
                        malformed("[$name] is listed twice")
                    }
                    parser.readField(name, found)
                }
                if (parser.nextToken() != null) {
                    malformed("trailing content after the document")
                }
            }
        } catch (error: JacksonException) {
            throw SnapshotFormatException("Snapshot document is not valid JSON.", error)
        }
        return found.header()
    }

    /** Reads the value of the top-level field [name] into [found], or skips it when it is not a header field. */
    private fun JsonParser.readField(name: String, found: Fields) {
        val token = nextToken()
        when (name) {
            SCHEMA -> found.schema = intField(name, token)
            REVISION -> found.revision = longField(name, token)
            PUBLISHED_AT -> found.publishedAt = instantField(name, token)
            else -> skipChildren()
        }
    }

    private fun JsonParser.intField(field: String, token: JsonToken?): Int {
        if (token != JsonToken.VALUE_NUMBER_INT || numberType != JsonParser.NumberType.INT) {
            malformed("[$field] is not an int")
        }
        return intValue
    }

    private fun JsonParser.longField(field: String, token: JsonToken?): Long {
        val type = numberType
        if (token != JsonToken.VALUE_NUMBER_INT || (type != JsonParser.NumberType.INT && type != JsonParser.NumberType.LONG)) {
            malformed("[$field] is not a long")
        }
        return longValue
    }

    private fun JsonParser.instantField(field: String, token: JsonToken?): Instant {
        if (token != JsonToken.VALUE_STRING) {
            malformed("[$field] is not a string")
        }
        return try {
            Instant.parse(string)
        } catch (_: DateTimeParseException) {
            malformed("[$field] is not an ISO-8601 instant")
        }
    }

    private fun malformed(reason: String): Nothing =
        throw SnapshotFormatException("Snapshot document is malformed: $reason.")
}
