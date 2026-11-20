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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ReferrerNames
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock

/**
 * `restore` (UC-6): the way back from a lock-out, for whoever holds the store's credentials, who could otherwise only
 * empty it. It writes a kept revision of a document, or a file, as that document's next revision through the control
 * plane's own writer ([Revisions.publish]): compiled as every instance compiles it, the revision record first, then
 * the head by a compare-and-set on the digest of the head it replaces, with its provenance. Nothing is edited: what is
 * written is the kept revision's content, or the file's.
 *
 * Provenance: kind `ROLLBACK` for a kept revision, `MIGRATE` for a file, by `cosec-migrate`, and the change field says
 * `restored from revision <n> by cosec-migrate restore` or `restored from a file by cosec-migrate restore`: the stored
 * format has no kind of its own for it. Nothing reads a document's provenance kind to decide (the lifecycle reads a
 * change's own kind; the chain reads `parent`), and the change text, holding spaces, is never a change id.
 *
 * It writes only when the caller confirms the head revision it replaces ([Plan.head]), which the plan prints: a head
 * that moved since, or moves while it writes, is refused and nothing is written. The control plane's change listeners
 * run in its host and do not hear of it, as of `publish` and `bootstrap`: the revision record is the only audit of a
 * restore, and the Kafka change audit does not carry it. Every instance installs the new head as it installs any.
 */
class DocumentRestorer(
    store: ControlStore,
    clock: Clock = Clock.systemUTC(),
) {
    private val revisions = Revisions(store, clock)

    /** What is to be written: [document] over [head], and the [source] it comes from, as the plan prints it. */
    class Plan internal constructor(
        val key: SnapshotKey,
        val head: Head,
        val document: PolicyDocument,
        val source: String
    )

    /** The plan of restoring [key] from kept [revision] or from [file]'s document; throws what it refuses. */
    fun plan(key: SnapshotKey, revision: Long?, file: PolicyDocument?): Plan = runBlocking {
        require((revision == null) != (file == null)) { "Restore from one of --revision and --file." }
        val head = revisions.head(key) ?: throw RestoreException("[$key] has no document: publish it (cosec-migrate publish).")
        val document = file ?: revisions.revision(key, revision!!)
            ?: throw RestoreException("[$key] keeps no revision $revision: restore from a file instead (--file).")
        require(revision == null || revision < head.revision) {
            "Revision $revision is the head of [$key]: there is nothing to restore."
        }
        val platform = if (key == SnapshotKey.Platform) {
            document
        } else {
            revisions.head(SnapshotKey.Platform)?.document
                ?: throw RestoreException("No platform document is published: restore it first (--document platform).")
        }
        // As every instance compiles it (PlatformCompiler): what this writes, every instance installs.
        val compiler = PolicyDocumentCompiler.forPlatform(platform)
        compiler.compile(document)
        compiler.checkReferences(document, platform)
        if (document.bindings.any { it.breakGlass }) {
            throw RestoreException(
                "[$key]'s source holds a break-glass binding: request break-glass through the control plane."
            )
        }
        Plan(key, head, document, if (file != null) "a file" else "revision $revision")
    }

    /**
     * Writes [plan] when [confirmed] is the head revision it replaces; the revision written. A head that moved since
     * the plan, or while it writes, is refused.
     */
    fun restore(plan: Plan, confirmed: Long): Long = runBlocking {
        if (confirmed != plan.head.revision) {
            throw RestoreException(
                "--confirm $confirmed is not the head [${plan.key}] is restored over, revision ${plan.head.revision}: " +
                    "read the plan again",
            )
        }
        val kind = if (plan.source == "a file") ProvenanceKind.MIGRATE else ProvenanceKind.ROLLBACK
        // By what, too: read alone, the record tells a restore from a rollback the control plane published.
        val publication = Publication(
            "restored from ${plan.source} by cosec-migrate restore",
            kind,
            DocumentPublisher.MIGRATE_ACTOR
        )
        when (val outcome = revisions.publish(plan.key, plan.head, plan.document, publication)) {
            is PublishOutcome.Published -> outcome.revision
            is PublishOutcome.Conflicted -> throw RestoreException(
                "[${plan.key}] moved while it was restored: nothing written; read the plan again",
            )
            is PublishOutcome.Referenced -> throw RestoreException(
                "[${plan.key}]'s source no longer defines what other documents refer to: ${ReferrerNames.of(outcome.referrers)}",
            )
        }
    }
}
