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

package me.ahoo.cosec.snapshot

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

/**
 * A stored snapshot document (RFC 0003 §3.3): the header is decoded, the body stays raw bytes until it is compiled.
 *
 * [revision] is assigned by the publisher and grows by one with every publish of the same key. It is metadata, not
 * a guard: Redis is the store of record, and a lower revision than the one installed is a regression to install and
 * alert on, never to ignore (see [SnapshotSource]).
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
        /** RFC 0003's 7.0-shaped documents (`policies` and the schema-1 `app`), removed at the switch. */
        const val CURRENT_SCHEMA = 1

        /** The platform, app and tenant documents (RFC 0004 §7.1): the one 8.0 format (RFC 0008 Q5). */
        const val DOCUMENT_SCHEMA = 2

        /** The formats a store may hold until the switch; each compiler accepts only its own. */
        val SCHEMAS: Set<Int> = setOf(CURRENT_SCHEMA, DOCUMENT_SCHEMA)
    }
}

/**
 * The digest of a stored document, as the [SnapshotDocumentStore] computes it from the bytes it stores. Only the store
 * that produced it can interpret it; [SnapshotSource] just hands it back on a conditional read.
 */
class SnapshotDigest(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = this === other || (other is SnapshotDigest && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.joinToString(separator = "") { "%02x".format(it) }
}

/**
 * The revisions of the two documents one evaluation read (RFC 0003 §3.4). [scoped] is 0 when the evaluation needed
 * no app document or the app has none. Phase 4 renames the pair to `(platform, tenant)`.
 */
data class SnapshotVersion(val shared: Long, val scoped: Long)
