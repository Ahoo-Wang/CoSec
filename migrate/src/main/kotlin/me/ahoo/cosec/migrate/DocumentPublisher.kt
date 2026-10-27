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

package me.ahoo.cosec.migrate

import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler

/** `publish` stopped: [written] are the keys it wrote before, exactly; nothing else was written. */
open class PublishException(message: String, val written: List<SnapshotKey>, cause: Throwable? = null) :
    IllegalStateException(
        message + if (written.isEmpty()) {
            " Nothing was written."
        } else {
            " Written before it stopped: $written; " +
                "run publish again with --resume to finish."
        },
        cause,
    )

/** 8.0 documents already exist for [keys] (and, with resume, differ from the converted ones): never overwritten. */
class DocumentsExistException(val keys: List<SnapshotKey>, written: List<SnapshotKey>, differ: Boolean = false) :
    PublishException(
        if (differ) {
            "8.0 documents for $keys exist and differ from the converted ones; publish never overwrites."
        } else {
            "8.0 documents already exist for $keys; publish never overwrites (--resume skips identical ones)."
        },
        written,
    )

/** What [DocumentPublisher.publish] did: the keys it wrote, and those it found already published identically. */
class PublishResult(val written: List<SnapshotKey>, val skipped: List<SnapshotKey>)

/**
 * Writes converted documents into the 8.0 snapshot cache (RFC 0004 §8.2 `publish`), each only if it does not exist
 * yet (a conditional write with no expected digest), so an existing 8.0 deployment is never overwritten. It does not
 * touch the 7.x keys: rolling back is not switching. The 8.0 schema marker belongs to the switch (RFC 0008 S1), so
 * publish does not write it.
 *
 * Every document is compiled first, and every app document's references are checked against the platform document,
 * as a publisher does before it writes (RFC 0004 §7.4): a document that would fail closed is never written.
 *
 * Writes are one per document, so a failure can leave a part written; the exception says exactly which keys. With
 * `resume`, a document already in Redis is skipped when it is byte for byte the converted one (its revision and
 * body), and refused otherwise, so a second run finishes the first.
 */
class DocumentPublisher(
    private val cache: PolicySnapshotCache,
    private val compiler: PolicyDocumentCompiler = PolicyDocumentCompiler(),
) {
    /** Publishes [documents], the platform document first. */
    fun publish(documents: Map<SnapshotKey, PolicyDocument>, resume: Boolean = false): PublishResult {
        val platform = requireNotNull(documents[SnapshotKey.Platform]) { "There is no platform document to publish." }
        documents.values.forEach { document ->
            compiler.compile(document)
            compiler.checkReferences(document, platform)
        }
        val ordered = documents.keys.sortedBy { ORDER.getValue(kindOf(it)) }
        val stored = ordered.associateWith { key -> stored(documents.getValue(key)) }
        val existing = ordered.mapNotNull { key -> cache[key]?.let { key to it } }.toMap()
        checkExisting(existing, stored, resume)
        return PublishResult(write(ordered.filterNot { it in existing }, stored), existing.keys.toList())
    }

    /** Refuses existing documents, unless [resume] and each is the converted one. */
    private fun checkExisting(
        existing: Map<SnapshotKey, SnapshotDocument>,
        stored: Map<SnapshotKey, SnapshotDocument>,
        resume: Boolean
    ) {
        if (existing.isNotEmpty() && !resume) {
            throw DocumentsExistException(existing.keys.toList(), emptyList())
        }
        val differing = existing.filter { (key, current) -> !same(current, stored.getValue(key)) }.keys
        if (differing.isNotEmpty()) {
            throw DocumentsExistException(differing.toList(), emptyList(), differ = true)
        }
    }

    /** Writes [keys] in order, each only if absent; the keys written. */
    @Suppress("TooGenericExceptionCaught")
    private fun write(keys: List<SnapshotKey>, stored: Map<SnapshotKey, SnapshotDocument>): List<SnapshotKey> {
        val async = cache.async()
        val written = ArrayList<SnapshotKey>()
        for (key in keys) {
            val result = try {
                async.putIfMatch(key, null, stored.getValue(key)).join()
            } catch (error: Exception) {
                throw PublishException("Writing $key failed: ${error.message}.", written.toList(), error)
            }
            when (result) {
                is ConditionalPut.Written -> written += key
                is ConditionalPut.Conflict -> throw DocumentsExistException(listOf(key), written.toList())
            }
        }
        return written
    }

    private fun stored(document: PolicyDocument) = SnapshotDocument(
        PolicyDocumentCodec.SCHEMA,
        document.revision,
        document.publishedAt,
        PolicyDocumentCodec.encode(document),
    )

    private fun same(a: SnapshotDocument, b: SnapshotDocument): Boolean =
        a.schema == b.schema && a.revision == b.revision && a.bodyBytes().contentEquals(b.bodyBytes())

    private fun kindOf(key: SnapshotKey): DocumentKind = when (key) {
        is SnapshotKey.App -> DocumentKind.APP
        is SnapshotKey.Tenant -> DocumentKind.TENANT
        else -> DocumentKind.PLATFORM
    }

    private companion object {
        val ORDER = mapOf(DocumentKind.PLATFORM to 0, DocumentKind.APP to 1, DocumentKind.TENANT to 2)
    }
}
