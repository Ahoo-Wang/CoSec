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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.change.CandidateDigest
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

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
 * Writes converted documents as their first revisions (RFC 0004 §8.2 `publish`, RFC 0007 §5.9) through the control
 * plane's [ControlStore], so the revision chain starts with them: a revision record, then the head, each only if the
 * document does not exist yet, so an existing 8.0 deployment is never overwritten. It does not touch the 7.x keys:
 * rolling back is not switching. The 8.0 schema marker belongs to the switch (RFC 0008 S1), so publish does not write it.
 *
 * Every document is compiled first, and every app document's references are checked against the platform document.
 * Documents that hold a break-glass binding are refused, nothing written (RC1-27): break-glass is requested through the
 * control plane after the switch, where two people take part in every step (RFC 0007 §8, `BRK-5`).
 *
 * Writes are one per document, so a failure can leave a part written; the exception says exactly which keys. With
 * `resume`, a document already published is skipped when its content is the converted one, and refused otherwise, so a
 * second run finishes the first.
 */
class DocumentPublisher(
    private val store: ControlStore,
    private val compiler: PolicyDocumentCompiler = PolicyDocumentCompiler(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val revisions = Revisions(store, clock)

    /** Publishes [documents], the platform document first. */
    fun publish(documents: Map<SnapshotKey, PolicyDocument>, resume: Boolean = false): PublishResult = runBlocking {
        val platform = requireNotNull(documents[SnapshotKey.Platform]) { "There is no platform document to publish." }
        documents.values.forEach { document ->
            compiler.compile(document)
            compiler.checkReferences(document, platform)
        }
        refuseBreakGlass(documents.values)
        val ordered = documents.keys.sortedBy { ORDER.getValue(kindOf(it)) }
        val existing = ordered.mapNotNull { key -> revisions.head(key)?.let { key to it.document } }.toMap()
        checkExisting(existing, documents, resume)
        PublishResult(write(ordered.filterNot { it in existing }, documents), existing.keys.toList())
    }

    private fun refuseBreakGlass(documents: Collection<PolicyDocument>) {
        refusedBreakGlass(documents)?.let { throw PublishException(it, emptyList()) }
    }

    private fun checkExisting(existing: Map<SnapshotKey, PolicyDocument>, converted: Map<SnapshotKey, PolicyDocument>, resume: Boolean) {
        if (existing.isNotEmpty() && !resume) {
            throw DocumentsExistException(existing.keys.toList(), emptyList())
        }
        val differing = existing.filter { (key, current) -> CandidateDigest.of(current) != CandidateDigest.of(converted.getValue(key)) }.keys
        if (differing.isNotEmpty()) {
            throw DocumentsExistException(differing.toList(), emptyList(), differ = true)
        }
    }

    /** Writes [keys] in order, each only if absent; the keys written. */
    private suspend fun write(keys: List<SnapshotKey>, documents: Map<SnapshotKey, PolicyDocument>): List<SnapshotKey> {
        val written = ArrayList<SnapshotKey>()
        for (key in keys) {
            when (val outcome = publish(key, documents.getValue(key), written)) {
                is PublishOutcome.Published -> written += key
                is PublishOutcome.Conflicted -> throw DocumentsExistException(listOf(key), written.toList())
                is PublishOutcome.Referenced -> throw PublishException(
                    "$key is referred to: ${outcome.referrers}.",
                    written.toList()
                )
            }
        }
        return written
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun publish(
        key: SnapshotKey,
        document: PolicyDocument,
        written: List<SnapshotKey>
    ): PublishOutcome = try {
        revisions.publish(key, null, document, PUBLICATION)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw PublishException("Writing $key failed: ${error.message}.", written.toList(), error)
    }

    private fun kindOf(key: SnapshotKey): DocumentKind = when (key) {
        is SnapshotKey.App -> DocumentKind.APP
        is SnapshotKey.Tenant -> DocumentKind.TENANT
        else -> DocumentKind.PLATFORM
    }

    companion object {
        /** `cosec.control.break-glass.max-duration` by default (RFC 0007 §8). */

        /** Who writes what `cosec-migrate` writes: recorded in each revision's provenance. */
        val MIGRATE_ACTOR: Actor = Actor(TenantId.PLATFORM, PrincipalId("cosec-migrate"))

        private val PUBLICATION = Publication("migrate", ProvenanceKind.MIGRATE, MIGRATE_ACTOR)
        private val ORDER = mapOf(DocumentKind.PLATFORM to 0, DocumentKind.APP to 1, DocumentKind.TENANT to 2)
    }
}

/**
 * Why [documents] are not written: the break-glass bindings they hold (RC1-27); null when they hold none. Shared by
 * `publish` and `bootstrap`.
 */
internal fun refusedBreakGlass(documents: Collection<PolicyDocument>): String? {
    val glass = documents.flatMap { document -> document.bindings.filter { it.breakGlass }.map { it.id.value } }
    return if (glass.isEmpty()) {
        null
    } else {
        "Break-glass binding(s) $glass: cosec-migrate writes no break-glass binding. After the switch, request " +
            "break-glass through the control plane, by two people (RFC 0007 §8)."
    }
}
