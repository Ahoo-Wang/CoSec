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
import me.ahoo.cosec.control.change.ControlStatus.NOT_FOUND
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
            SECTIONS.filter { CandidateDigest.of(it.alone(before)) != CandidateDigest.of(it.alone(after)) }.map { it.name },
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

    internal companion object {
        /** The members that say which document and revision it is, and how it came to be: never compared. */
        val HEADER: Set<String> = setOf("kind", "owner", "revision", "publishedAt", "provenance")

        /** The members the diff lists entry by entry. */
        val ENTRIES: Set<String> = setOf("policies", "bindings", "roles")

        /**
         * Every other member of [PolicyDocument], each a section the diff names when it differs, in the document
         * format's order, each compared by its whole content encoded alone (#1205). A member of a document is in
         * exactly one of [HEADER], [ENTRIES] and these (`DiffMembersTest`), so one added later is never left uncompared.
         */
        val SECTIONS: List<Section> = listOf(
            Section("catalog", { it.copy(catalog = null) }) { bare, of -> bare.copy(catalog = of.catalog) },
            Section("boundary", { it.copy(boundary = null) }) { bare, of -> bare.copy(boundary = of.boundary) },
            // The format's `limits` holds two members: the condition limits and the entry budgets (CTL-40).
            Section("limits", { it.copy(limits = null, budgets = null) }, setOf("limits", "budgets")) { bare, of ->
                bare.copy(limits = of.limits, budgets = of.budgets)
            },
            Section("attributes", { it.copy(attributes = null) }) { bare, of -> bare.copy(attributes = of.attributes) },
            Section(
                "rateLimits",
                { it.copy(rateLimits = emptyList()) }
            ) { bare, of -> bare.copy(rateLimits = of.rateLimits) },
        )

        /** [document] with its header fixed and nothing in it: no entries, and every section cleared. */
        private fun bare(document: PolicyDocument): PolicyDocument = SECTIONS.fold(
            document.copy(
                revision = 1,
                publishedAt = Instant.EPOCH,
                policies = emptyList(),
                bindings = emptyList(),
                provenance = null,
                roles = emptyList(),
            )
        ) { bare, section -> section.clear(bare) }
    }

    /**
     * A section of a document, [name] as the format writes it: [clear] takes it out of one, [take] puts its content of
     * another into a bare one. [members] are the members of [PolicyDocument] it holds: its name alone, unless the
     * format writes more than one member under it.
     */
    internal class Section(
        val name: String,
        val clear: (PolicyDocument) -> PolicyDocument,
        val members: Set<String> = setOf(name),
        private val take: (PolicyDocument, PolicyDocument) -> PolicyDocument,
    ) {
        /** [document] with this section alone. */
        fun alone(document: PolicyDocument): PolicyDocument = take(bare(document), document)
    }
}
