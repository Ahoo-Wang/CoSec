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

package me.ahoo.cosec.spi.snapshot

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.HexFormat

/**
 * A stored snapshot document (RFC 0003 §3.3): the header is decoded, the body stays raw bytes until it is compiled.
 *
 * [revision] is assigned by the publisher and grows by one with every publish of the same key. It is metadata, not
 * a guard: Redis is the store of record, and a lower revision than the one installed is a regression to install and
 * alert on, never to ignore (see `SnapshotSource`).
 *
 * The body is copied in and never handed out, so a document can be shared between threads and compiled more than once.
 */
class SnapshotDocument(
    /** The document format version, not the Redis layout marker. */
    val schema: Int,
    val revision: Long,
    val publishedAt: Instant,
    body: ByteArray,
) {
    private val body: ByteArray = body.copyOf()

    init {
        require(revision > 0) { "Revision must be positive, was $revision." }
    }

    val bodySize: Int get() = body.size

    fun bodyStream(): InputStream = ByteArrayInputStream(body)

    fun bodyBytes(): ByteArray = body.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnapshotDocument) return false
        return schema == other.schema &&
            revision == other.revision &&
            publishedAt == other.publishedAt &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = schema
        result = 31 * result + revision.hashCode()
        result = 31 * result + publishedAt.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }

    override fun toString(): String =
        "SnapshotDocument(schema=$schema, revision=$revision, publishedAt=$publishedAt, bodySize=$bodySize)"

    companion object {
        /** The platform, app and tenant documents (RFC 0004 §7.1): the one 8.0 format (RFC 0008 Q5). */
        const val DOCUMENT_SCHEMA = 2
    }
}

/**
 * The digest of a stored document, as the [SnapshotDocumentStore] computes it from the bytes it stores: a SHA-256 hash
 * that names its [algorithm]. Only the store that produced it can interpret it; `SnapshotSource` just hands it back on a
 * conditional read.
 *
 * Its text form, [toString], is `SHA-256:` and 64 lowercase hex digits ([PATTERN]), as CoCache writes its digests. The
 * control plane persists digests in that form (revision record keys, revision indexes, a provenance's parent, a change's
 * base and checks), and [parse] reads it back.
 */
class SnapshotDigest(val algorithm: String, bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(algorithm == SHA_256) { "A snapshot digest is $SHA_256, not [$algorithm]." }
        require(this.bytes.size == SHA_256_BYTES) {
            "A $SHA_256 digest is $SHA_256_BYTES bytes, not ${this.bytes.size}."
        }
    }

    /** A SHA-256 digest of [bytes], 32 bytes. */
    constructor(bytes: ByteArray) : this(SHA_256, bytes)

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is SnapshotDigest && algorithm == other.algorithm && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * algorithm.hashCode() + bytes.contentHashCode()

    /** `SHA-256:<64 lowercase hex digits>`. */
    override fun toString(): String = algorithm + SEPARATOR + HEX.formatHex(bytes)

    companion object {
        const val SHA_256: String = "SHA-256"
        const val SHA_256_BYTES: Int = 32

        /** The text form of a digest, as [toString] writes it. */
        const val PATTERN: String = "^SHA-256:[0-9a-f]{64}$"

        private const val SEPARATOR = ':'
        private val HEX: HexFormat = HexFormat.of()
        private val TEXT = Regex(PATTERN)

        /** Whether [text] is a digest in its text form. */
        fun isDigest(text: String): Boolean = TEXT.matches(text)

        /** The digest [text] writes; [IllegalArgumentException] when it is not one ([PATTERN]). */
        fun parse(text: String): SnapshotDigest {
            require(isDigest(text)) { "[$text] is not a $SHA_256 digest." }
            val hex = text.substring(SHA_256.length + 1)
            return SnapshotDigest(SHA_256, HEX.parseHex(hex))
        }
    }
}
