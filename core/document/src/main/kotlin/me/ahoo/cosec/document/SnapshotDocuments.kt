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
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.ObjectReader
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The one way to make a [SnapshotDocument] (RFC 0003 §3.3, SNP-12): its body is the record, the whole JSON document
 * with its header, and the header (`schema`, `revision`, `publishedAt`) is read from it. No one can state a header the
 * body contradicts, so every revision an instance reports is the revision of the content it enforces.
 *
 * A store of your own that keeps the bytes returns `SnapshotDocuments.read(bytes)`: a streaming pass with no data
 * binding (RFC 0003 §10), which still walks the whole document, so that a repeated key at any depth, trailing content
 * or broken JSON fails here, as the compiler's strict parser would.
 */
object SnapshotDocuments {
    private const val SCHEMA = "schema"
    private const val REVISION = "revision"
    private const val PUBLISHED_AT = "publishedAt"
    private const val UTF_DETECTION_BYTES = 4

    private val reader: ObjectReader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    /**
     * The document [bytes] hold, with the header they carry. A [SnapshotFormatException] when they are not a JSON object
     * in UTF-8 without a byte order mark, repeat a key, lack a header field or mistype one, carry a revision below 1, or
     * a schema other than this version's ([SnapshotDocument.DOCUMENT_SCHEMA]).
     */
    fun read(bytes: ByteArray): SnapshotDocument {
        val header = readHeader(bytes)
        return SnapshotDocument(header.schema, header.revision, header.publishedAt, bytes)
    }

    /** [document] as it is stored: its encoding, with the header it states (RFC 0004 §7.1). */
    @InternalCoSecApi
    fun of(document: PolicyDocument): SnapshotDocument = SnapshotDocument(
        PolicyDocumentCodec.SCHEMA,
        document.revision,
        document.publishedAt,
        PolicyDocumentCodec.encode(document),
    )

    private class Header(val schema: Int, val revision: Long, val publishedAt: Instant)

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

    private fun readHeader(bytes: ByteArray): Header {
        requireUtf8(bytes)
        val found = Fields()
        try {
            reader.createParser(bytes).use { parser -> parser.readFields(found) }
        } catch (error: JacksonException) {
            throw SnapshotFormatException("Snapshot document is not valid JSON.", error)
        }
        return found.header()
    }

    private fun JsonParser.readFields(found: Fields) {
        if (nextToken() != JsonToken.START_OBJECT) {
            malformed("the document is not a JSON object")
        }
        val names = HashSet<String>()
        while (nextToken() == JsonToken.PROPERTY_NAME) {
            val name = currentName()
            // The strict parser rejects it too; this check does not depend on a parser feature.
            if (!names.add(name)) {
                malformed("[$name] is listed twice")
            }
            readField(name, found)
        }
        if (nextToken() != null) {
            malformed("trailing content after the document")
        }
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

    /** Stored content quoted in [reason] has its control characters escaped: the message reaches log lines (RC1-15). */
    private fun malformed(reason: String): Nothing =
        throw SnapshotFormatException("Snapshot document is malformed: ${SnapshotKeyText.printable(reason)}.")
}
