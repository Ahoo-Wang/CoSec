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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.BAD_REQUEST
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.BREAK_GLASS
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.CONFLICT
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.GONE
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.NOT_FOUND
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.REBASABLE
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock

/**
 * Proposing a change (RFC 0007 §6.1, §6.7, §5.6): checked against its base, as a `PUBLISH` or a `BREAK_GLASS` change,
 * as the operations that make a head into a desired document, or as a rollback; and rebasing one on the current head.
 * [Changes.propose], [Changes.apply], [Changes.rollback] and [Changes.rebase] are its entries.
 */
internal class Proposals(
    private val flow: ChangeLifecycle,
    private val store: ControlStore,
    private val revisions: Revisions,
    private val ids: () -> ChangeId,
    private val clock: Clock,
    private val authority: ChangeAuthority,
    private val candidates: CandidateChecks,
    private val listeners: ChangeListeners,
) {
    /**
     * Proposes [operations] on revision [base] of [key] (0 for a document not published yet) and checks them. The
     * change is `CHECKED` when every check passes, `DRAFT` with its findings otherwise. A base that is not the head is
     * a 409: the author proposes on what they have seen. The [author] must be allowed every target first (403, 503).
     *
     * A caller proposes a `PUBLISH` change, or a `BREAK_GLASS` one through [BreakGlass]; any other [kind] is a 400.
     * `ROLLBACK` is [rollback]'s, and `MIGRATE` and `BOOTSTRAP` revisions are written by cosec-migrate, never by a
     * change (review K1): a kind is what an entry point is, never what its input says.
     */
    suspend fun propose(
        author: Operator,
        key: SnapshotKey,
        base: Long,
        operations: List<Operation>,
        note: String,
        kind: ProvenanceKind = ProvenanceKind.PUBLISH,
        /** `deny-nomatch` confirmations of the statements that need them (RFC 0007 §9.5). */
        confirmations: List<Confirmation> = emptyList(),
    ): ChangeRequest {
        if (kind != ProvenanceKind.PUBLISH && kind != ProvenanceKind.BREAK_GLASS) {
            throw ChangeRejectedException(BAD_REQUEST, "a change is proposed as PUBLISH or BREAK_GLASS, not $kind")
        }
        return proposeAs(author, key, base, operations, note, kind, confirmations)
    }

    private suspend fun proposeAs(
        author: Operator,
        key: SnapshotKey,
        base: Long,
        operations: List<Operation>,
        note: String,
        kind: ProvenanceKind,
        confirmations: List<Confirmation>,
    ): ChangeRequest {
        val head = revisions.head(key)
        // Keyed on what the change does, not on its kind (review K1).
        val breakGlass = BreakGlassRules.touches(operations, head?.document, clock.instant())
        val glass = breakGlass || kind == ProvenanceKind.BREAK_GLASS
        authority.authorize(author, key, operations, kind, glass, head?.document)
        PublishRules.requireAudit(breakGlass, listeners.hasRequired)
        if ((head?.revision ?: 0) != base) {
            throw ChangeRejectedException(CONFLICT, "[$key] is at revision ${head?.revision ?: 0}, not $base")
        }
        val now = clock.instant()
        val draft = ChangeRequest(
            id = ids(),
            key = key,
            base = head?.let { Base(it.revision, it.digest) } ?: Base.NONE,
            operations = operations,
            author = author.actor,
            note = note,
            kind = kind,
            state = ChangeState.DRAFT,
            findings = emptyList(),
            candidate = null,
            checkedWith = emptyMap(),
            createdAt = now,
            updatedAt = now,
            confirmations = confirmations,
        )
        val checked = candidates.checked(draft, head, author)
        if (breakGlass) {
            flow.audit(ChangeEvent.Kind.BREAK_GLASS_REQUESTED, checked, author.actor)
        }
        if (!store.putChange(null, checked)) {
            throw ChangeRejectedException(CONFLICT, "change ${checked.id.value} already exists")
        }
        flow.audit(
            if (checked.state == ChangeState.CHECKED) ChangeEvent.Kind.CHECKED else ChangeEvent.Kind.PROPOSED,
            checked,
            author.actor
        )
        return checked
    }

    /** `apply` (RFC 0007 §6.7): proposes the operations that make the head of [key] into [desired]. */
    suspend fun apply(
        author: Operator,
        key: SnapshotKey,
        desired: PolicyDocument,
        mode: ApplyMode,
        note: String
    ): ChangeRequest {
        val head = revisions.head(key)
        val (kind, owner) = ownerOf(key)
        requireRequest(desired.kind == kind && desired.owner == owner) {
            "The desired document is not a document of [$key]."
        }
        return propose(author, key, head?.revision ?: 0, Operations.diff(key, head?.document, desired, mode), note)
    }

    /**
     * Rollback (RFC 0007 §5.6): a change that makes [key] what it was at [revision], checked like any other: every
     * break-glass rule keys on what it does (a rollback that would bring a break-glass binding back never checks clean),
     * and so do the reserved names, the grant analysis, the approval and the shadow window. The change shows the old
     * revision's entries, and whether a revision exists is history, so [author] needs `cosec:read` on the document
     * before either is looked at (round-2 review R5). Like any change, one that restores a `nomatch` DENY needs its
     * [confirmations] (RFC 0007 §9.5): the old revision's own confirmation was another person's, of another change.
     */
    suspend fun rollback(
        author: Operator,
        key: SnapshotKey,
        revision: Long,
        note: String,
        confirmations: List<Confirmation> = emptyList(),
    ): ChangeRequest {
        authority.require(author, key, ControlActions.READ)
        val head = revisions.head(key) ?: throw ChangeRejectedException(NOT_FOUND, "[$key] has no document")
        val body = revisions.revision(key, revision)
            ?: throw ChangeRejectedException(GONE, "[$key] keeps no revision $revision")
        val operations = Operations.diff(key, head.document, body, ApplyMode.REPLACE)
        return proposeAs(author, key, head.revision, operations, note, ProvenanceKind.ROLLBACK, confirmations)
    }

    /**
     * Replays a `CONFLICTED` change (or one not staged yet: `DRAFT`, `CHECKED`) on the current head and runs every
     * check again (RFC 0007 §5.5): the candidate, its digest and its findings are new, and nothing earlier checks or
     * approvals said carries over. Only its author rebases it, and is checked again as they are now.
     */
    suspend fun rebase(author: Operator, id: ChangeId): ChangeRequest {
        val change = flow.visible(author, id)
        authority.requireAuthor(author, change, "rebases")
        PublishRules.requireState(change, REBASABLE)
        candidates.authorize(author, change)
        return flow.transition(id, REBASABLE) { current ->
            val head = revisions.head(current.key)
            val base = head?.let { Base(it.revision, it.digest) } ?: Base.NONE
            candidates.checked(
                current.copy(base = base, skipShadow = null, approval = null, shadow = null),
                head,
                author
            )
        }
    }
}
