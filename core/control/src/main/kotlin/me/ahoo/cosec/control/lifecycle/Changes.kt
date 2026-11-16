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
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ShadowAck
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
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock

/**
 * Change requests and their state machine (RFC 0007 §6.1): propose, check, stage, rebase, publish, abandon, `apply`
 * and rollback, over a [ControlStore] and its [Revisions].
 *
 * - **Checked against a base.** A change is its base (revision and digest of the head it was made on) and its
 *   entry-level operations. Every check runs on the candidate those make and records the candidate's digest, so a
 *   check never outlives its base: a moved head makes the change `CONFLICTED`, and [rebase] replays the operations
 *   on the new head and runs every check again.
 * - **Compiled before publishing.** [publish] compiles the candidate again with the data plane's compiler against
 *   the current heads, then writes with `putIfMatch(expected = base digest)`. A candidate that does not compile, a
 *   moved head, or a removed policy still referred to never reaches the head; the head is then unchanged.
 * - **One staged change per document** (RFC 0007 §5.5): staging takes the document's slot, a conditional write, and
 *   publishing, abandoning or expiring releases it. A slot whose holder is gone, finished or no longer staged is free.
 *
 * - **Authorized, analyzed, approved** (RFC 0007 §7.2–§7.5, [ChangeAuthority]). Every step names its [Operator] and
 *   is checked before it writes: the hard rule of tenant isolation, then each target with the control plane's
 *   authorizer. A proposal is analyzed for what it grants; what the analysis cannot prove contained is an escalation,
 *   and a change with escalations waits in `AWAITING_APPROVAL` once its window closes, until a person other than its
 *   author holding `cosec:approve` approves its candidate digest. [publish] runs every check again as the publisher,
 *   on the current heads, and publishes only with an approval that is still valid for what that check found.
 *
 * - **Shadowed before publishing** (RFC 0007 §6.4). [stage] writes the candidate the data plane's shadow listeners compare
 *   against; [shadowed] closes the window from their reports, and [publish] needs the publisher to acknowledge the
 *   numbers whenever anything changed or the evidence was insufficient.
 */
// B4: the change flow becomes a transition table
@Suppress("TooManyFunctions")
@InternalCoSecApi
class Changes(
    private val store: ControlStore,
    private val revisions: Revisions,
    private val ids: () -> ChangeId,
    authorizer: ControlAuthorizer,
    private val clock: Clock = Clock.systemUTC(),
    private val options: ChangeOptions = ChangeOptions(),
    /** The host's `AttributeProvider` catalog, which candidates compile against with the platform's own declarations. */
    attributes: AttributeCatalog = AttributeCatalog.NONE,
    /** The headers the host reads credentials from besides the fixed ones (`CND-5`), as enforcement compiles with them. */
    credentialHeaders: Set<String> = emptySet(),
    private val checks: ChangeChecks = ChangeChecks(options, attributes, credentialHeaders),
    internal val authority: ChangeAuthority = ChangeAuthority(authorizer, options),
    /** What the instances report of the shadow windows (RFC 0007 §6.4); none by default. */
    reports: ShadowReportReader = InMemoryShadowReports(clock),
    /** Who hears of every step (RFC 0007 §10.5); publishing and break-glass wait for the REQUIRED ones. */
    private val listeners: ChangeListeners = ChangeListeners.NONE,
) {
    private val windows = ShadowWindows(reports, options.shadow)

    private val context = ChangeContext(store, revisions)

    /** This instance has reconciled the app list ([me.ahoo.cosec.control.lifecycle.ControlMaintenance.reconcileApps]). */
    internal fun appListReconciled() = context.reconciled()

    private val movedHeads = MovedHeads(store, authority, context, clock)

    private val candidates = CandidateChecks(checks, context, authority, clock, options)

    internal val preemption = Preemption(revisions, authority, clock)

    private val slots = StagingSlots(store, clock, options)

    private val flow = ChangeLifecycle(store, clock, options, slots, listeners, authority)
    private val publishing = Publishing(flow, revisions, clock, authority, candidates, slots, listeners, movedHeads)
    private val staging = Staging(
        flow,
        publishing,
        store,
        revisions,
        clock,
        authority,
        candidates,
        slots,
        windows,
        preemption,
    )
    private val approvals = Approvals(flow, revisions, clock, authority, slots, listeners, preemption)

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

    /** The change [id]; one past its expiry is `EXPIRED` from then on. */
    suspend fun get(id: ChangeId): ChangeRequest = flow.get(id)

    /** [change], as [get] answers for it, when the caller has just read it from the store and need not read it again. */
    internal suspend fun current(change: ChangeRequest): ChangeRequest = flow.current(change)

    /** [ChangeLifecycle.visible]: the change [id] as [operator] may see it, the same 404 when they may not. */
    suspend fun visible(operator: Operator, id: ChangeId): ChangeRequest = flow.visible(operator, id)

    /** The change holding [key]'s staging slot, `EXPIRED` once past its expiry; `null` when none does. */
    internal suspend fun stagedOn(key: SnapshotKey): ChangeRequest? = flow.stagedOn(key)

    /** [Publishing.publish]: publishes a `READY` change, or settles one whose revision is written. */
    suspend fun publish(
        publisher: Operator,
        id: ChangeId,
        ack: ShadowAck? = null,
        again: String? = null
    ): ChangeRequest =
        publishing.publish(publisher, id, ack, again)

    /** [Publishing.candidateOf]: the candidate [id] would publish on the current head, or null. */
    suspend fun candidateOf(
        id: ChangeId
    ): PolicyDocument? = publishing.candidateOf(id)

    /** [Staging.stage]: takes the staging slot for a `CHECKED` change and starts its shadow window. */
    suspend fun stage(operator: Operator, id: ChangeId): ChangeRequest = staging.stage(operator, id)

    /** [Staging.shadowed]: looks at the window of a `SHADOWING` change, and closes it once it may. */
    suspend fun shadowed(id: ChangeId): ChangeRequest = staging.shadowed(id)

    /** [Staging.propagation]: the propagation of [revision] of [key] to the instances. */
    suspend fun propagation(key: SnapshotKey, revision: Long): Propagation = staging.propagation(key, revision)

    /** [Staging.skipShadow]: skips the window of a `CHECKED` change, with a [reason]. */
    suspend fun skipShadow(operator: Operator, id: ChangeId, reason: String): ChangeRequest =
        staging.skipShadow(
            operator,
            id,
            reason
        )

    /** [Approvals.approve]: approves an `AWAITING_APPROVAL` change's [candidate]. */
    suspend fun approve(operator: Operator, id: ChangeId, candidate: String): ChangeRequest =
        approvals.approve(operator, id, candidate)

    /** [Approvals.reject]: rejects an `AWAITING_APPROVAL` change, with a [reason]. */
    suspend fun reject(operator: Operator, id: ChangeId, reason: String): ChangeRequest = approvals.reject(
        operator,
        id,
        reason
    )

    /** [Approvals.abandon]: abandons a change that is not finished. */
    suspend fun abandon(operator: Operator, id: ChangeId): ChangeRequest = approvals.abandon(operator, id)
}
