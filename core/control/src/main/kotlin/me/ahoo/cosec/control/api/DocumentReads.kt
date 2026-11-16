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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.CandidateDigest
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.EntryDigest
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/** What the control API reads of a document: its head, its history, the diff of two revisions, how far a revision propagated. */
internal class DocumentReads(
    private val authority: ChangeAuthority,
    private val revisions: Revisions,
    private val changes: Changes,
    private val retained: RetainedDocuments,
) {
    suspend fun read(operator: Operator, key: SnapshotKey, revision: Long?): PolicyDocument {
        authority.require(operator, key, ControlActions.READ)
        if (revision == null) {
            return revisions.head(key)?.document ?: throw ChangeRejectedException(NOT_FOUND, "[$key] has no document")
        }
        return retained.retained(key, revision)
    }

    suspend fun history(operator: Operator, key: SnapshotKey, before: Long?, limit: Int): List<RevisionSummary> {
        authority.require(operator, key, ControlActions.READ)
        requireRequest(
            limit in 1..ControlPlane.MAX_LIMIT
        ) { "The limit is 1 to ${ControlPlane.MAX_LIMIT}, not $limit." }
        return revisions.history(
            key
        ).filter { before == null || it.revision < before }.sortedByDescending { it.revision }
            .take(limit).map { RevisionSummary(it.revision, it.digest.toString(), it.publishedAt) }
    }

    suspend fun diff(operator: Operator, key: SnapshotKey, from: Long, to: Long): DocumentDiff {
        authority.require(operator, key, ControlActions.READ)
        val before = retained.retained(key, from)
        val after = retained.retained(key, to)
        val (kind, owner) = ownerOf(key)
        val expandedBefore = RoleExpansion.effective(before)
        val expandedAfter = RoleExpansion.effective(after)
        return DocumentDiff(
            from,
            to,
            changes(
                expandedBefore.policies.associate { it.id.value to EntryDigest.of(it) },
                expandedAfter.policies.associate { it.id.value to EntryDigest.of(it) }
            ),
            changes(
                expandedBefore.bindings.associate { it.id.value to EntryDigest.of(it, kind, owner) },
                expandedAfter.bindings.associate { it.id.value to EntryDigest.of(it, kind, owner) },
            ),
            changes(
                before.roles.associate { it.id.value to EntryDigest.of(it) },
                after.roles.associate { it.id.value to EntryDigest.of(it) },
            ),
            sections(before) != sections(after),
        )
    }

    suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation {
        authority.require(operator, key, ControlActions.READ)
        return changes.propagation(key, revision)
    }

    private fun changes(before: Map<String, String>, after: Map<String, String>) = EntryChanges(
        (after.keys - before.keys).sorted(),
        (before.keys - after.keys).sorted(),
        after.keys.filter { it in before && before[it] != after[it] }.sorted(),
    )

    /** The digest of [document]'s sections: the document encoded with its header fixed and no policies, bindings or roles. */
    private fun sections(document: PolicyDocument): String = CandidateDigest.of(
        document.copy(
            revision = 1,
            publishedAt = Instant.EPOCH,
            policies = emptyList(),
            bindings = emptyList(),
            provenance = null,
            roles = emptyList(),
        )
    )

    private companion object {
        const val NOT_FOUND = 404
    }
}
