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

/** `publish` found 8.0 documents already in Redis, or lost a race for one: nothing of its own was overwritten. */
class DocumentsExistException(val keys: List<SnapshotKey>, val written: List<SnapshotKey>) :
    IllegalStateException(
        "8.0 documents already exist for $keys; publish never overwrites. " +
            if (written.isEmpty()) "Nothing was written." else "Written before the conflict: $written."
    )

/**
 * Writes converted documents into the 8.0 snapshot cache (RFC 0004 §8.2 `publish`), each only if it does not exist
 * yet (a conditional write with no expected digest), so an existing 8.0 deployment is never overwritten. It does not
 * touch the 7.x keys: rolling back is not switching. The 8.0 schema marker belongs to the switch (RFC 0008 S1), so
 * publish does not write it.
 *
 * Every document is compiled first, and every app document's references are checked against the platform document,
 * as a publisher does before it writes (RFC 0004 §7.4): a document that would fail closed is never written.
 */
class DocumentPublisher(
    private val cache: PolicySnapshotCache,
    private val compiler: PolicyDocumentCompiler = PolicyDocumentCompiler(),
) {
    /** Publishes [documents], the platform document first; returns the keys written. */
    fun publish(documents: Map<SnapshotKey, PolicyDocument>): List<SnapshotKey> {
        val platform = requireNotNull(documents[SnapshotKey.Platform]) { "There is no platform document to publish." }
        documents.values.forEach { document ->
            compiler.compile(document)
            compiler.checkReferences(document, platform)
        }
        val ordered = documents.keys.sortedBy { ORDER.getValue(kindOf(it)) }
        val existing = ordered.filter { cache[it] != null }
        if (existing.isNotEmpty()) {
            throw DocumentsExistException(existing, emptyList())
        }
        val async = cache.async()
        val written = ArrayList<SnapshotKey>()
        for (key in ordered) {
            val document = documents.getValue(key)
            val stored = SnapshotDocument(
                PolicyDocumentCodec.SCHEMA,
                document.revision,
                document.publishedAt,
                PolicyDocumentCodec.encode(document),
            )
            when (async.putIfMatch(key, null, stored).join()) {
                is ConditionalPut.Written -> written += key
                is ConditionalPut.Conflict -> throw DocumentsExistException(listOf(key), written)
            }
        }
        return written
    }

    private fun kindOf(key: SnapshotKey): DocumentKind = when (key) {
        is SnapshotKey.App -> DocumentKind.APP
        is SnapshotKey.Tenant -> DocumentKind.TENANT
        else -> DocumentKind.PLATFORM
    }

    private companion object {
        val ORDER = mapOf(DocumentKind.PLATFORM to 0, DocumentKind.APP to 1, DocumentKind.TENANT to 2)
    }
}
