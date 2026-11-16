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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.Approval
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.change.ShadowState
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.APPROVAL
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.BAD_REQUEST
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.BREAK_GLASS
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.BREAK_GLASS_EXEMPT
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.CONFLICT
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.FORBIDDEN
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.GONE
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.MAX_ATTEMPTS
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.NOT_FOUND
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.OPEN
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.REBASABLE
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.STAGED
import me.ahoo.cosec.control.lifecycle.ChangeLifecycle.Companion.UNAVAILABLE
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

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

    /**
     * Takes the document's staging slot for a `CHECKED` change and starts its shadow window (RFC 0007 §6.4): the
     * candidate goes to the `candidate:` namespace, named by the window, with the revision of its base, and the change is
     * `SHADOWING`. 409 when the slot is held; a candidate that cannot be written gives the slot back (503). The slot holds
     * the document's publishing up, so [operator] needs what publishing it needs ([PublishRules.publishing]).
     *
     * Creating or revoking a break-glass binding has no window (RFC 0007 Q12): such a change goes on at once, as if
     * skipped, without `cosec:skip-shadow`.
     */
    suspend fun stage(operator: Operator, id: ChangeId): ChangeRequest {
        val change = flow.visible(operator, id)
        authority.require(operator, change.key, PublishRules.publishing(change))
        PublishRules.requireState(change, setOf(ChangeState.CHECKED))
        val head = revisions.head(change.key)
        if (PublishRules.exempt(change, head)) {
            return reserved(operator, change, head) {
                it.copy(state = PublishRules.afterWindow(it), skipShadow = BREAK_GLASS_EXEMPT)
            }
        }
        val now = clock.instant()
        val state = ShadowState("${id.value}@${now.toEpochMilli()}", now)
        val candidate = slots.candidate(change, state) { candidates.applied(change, head?.document).first!! }
        val shadowing = reserved(operator, change, head) { it.copy(state = ChangeState.SHADOWING, shadow = state) }
        slots.write(shadowing, candidate) { undo(shadowing) }
        flow.audit(ChangeEvent.Kind.SHADOW_STARTED, shadowing, operator.actor)
        return shadowing
    }

    /**
     * [change] moved out of `CHECKED` by [next], and only then given its document's staging slot (#1235): a holder is in
     * a staged state from the moment it holds the slot, so a `CHECKED` holder is one nothing is working on any more, and
     * no call still writing its candidate is taken for it. The move, one conditional write, decides between two calls
     * staging one change. A change that does not get the slot, for whatever reason, is `CHECKED` again.
     */
    private suspend fun reserved(
        operator: Operator,
        change: ChangeRequest,
        head: Head?,
        next: suspend (ChangeRequest) -> ChangeRequest,
    ): ChangeRequest {
        val reserved = flow.transition(change.id, setOf(ChangeState.CHECKED), next)
        var taken = false
        try {
            takeSlot(operator, reserved, head)
            taken = true
            return reserved
        } finally {
            if (!taken) unreserve(reserved)
        }
    }

    /** Gives [reserved] back ([StagingSlots.giveBack]), and makes the change `CHECKED` again unless it has moved on. */
    private suspend fun unreserve(reserved: ChangeRequest) = slots.giveBack(reserved) { undo(reserved) }

    private suspend fun undo(reserved: ChangeRequest) {
        flow.transition(reserved.id, setOf(reserved.state)) {
            it.copy(state = ChangeState.CHECKED, skipShadow = null, shadow = null)
        }
    }

    /**
     * Looks at the window of a `SHADOWING` change (RFC 0007 §6.4; the host calls it periodically). While it may not
     * close yet, the change stays as it is. Once it may, its summary is kept as the evidence the publisher acknowledges,
     * the candidate is withdrawn, and the change is `READY`, or `AWAITING_APPROVAL` when it has escalations (§7.5). A
     * head that moved during the window makes it `CONFLICTED`. Reports that cannot be read keep the window open (503).
     */
    suspend fun shadowed(id: ChangeId): ChangeRequest {
        val change = flow.get(id)
        PublishRules.requireState(change, setOf(ChangeState.SHADOWING))
        val state = change.shadow ?: error("change ${id.value} is shadowing without a window")
        if (revisions.head(change.key)?.digest != change.base.digest) {
            return flow.settle(change, ChangeState.CONFLICTED, from = setOf(ChangeState.SHADOWING))
        }
        @Suppress("TooGenericExceptionCaught")
        val look = try {
            windows.look(state, clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChangeRejectedException(UNAVAILABLE, "the shadow reports of ${state.window} could not be read", e)
        }
        // Every instance seen is kept, so that one dropped from the window's reporters later still has to report.
        val seen = state.copy(reporters = look.reporters)
        val summary = look.summary ?: return if (seen == state) {
            change
        } else {
            flow.transition(id, setOf(ChangeState.SHADOWING)) { it.copy(shadow = seen) }
        }
        val closed = flow.transition(id, setOf(ChangeState.SHADOWING)) {
            it.copy(state = PublishRules.afterWindow(it), shadow = seen.copy(closed = summary))
        }
        slots.withdraw(closed)
        flow.audit(ChangeEvent.Kind.SHADOWED, closed, closed.author)
        return closed
    }

    /** The propagation of [revision] of [key] to the instances (RFC 0007 §6.5): read-only, nothing waits for it. */
    suspend fun propagation(key: SnapshotKey, revision: Long): Propagation = windows.propagation(key, revision)

    /**
     * Skips the window of a `CHECKED` change (`cosec:skip-shadow`, RFC 0007 Q1): it takes the slot and is `READY`. The
     * [reason] is kept on the change and recorded in the revision's provenance as a skipped window. [operator] needs
     * `cosec:skip-shadow` on the document, unless the change only creates or revokes break-glass bindings (Q12); a change
     * with escalations goes on to `AWAITING_APPROVAL`.
     */
    suspend fun skipShadow(operator: Operator, id: ChangeId, reason: String): ChangeRequest {
        requireRequest(reason.isNotBlank()) { "Skipping the shadow window needs a reason." }
        val change = flow.visible(operator, id)
        if (PublishRules.exempt(change, revisions.head(change.key))) {
            authority.require(operator, change.key, PublishRules.publishing(change))
        } else {
            authority.require(operator, change.key, ControlActions.SKIP_SHADOW)
        }
        PublishRules.requireState(change, setOf(ChangeState.CHECKED))
        return reserved(operator, change, revisions.head(change.key)) {
            it.copy(state = PublishRules.afterWindow(it), skipShadow = reason)
        }
    }

    /**
     * Approves an `AWAITING_APPROVAL` change (RFC 0007 §7.5): [operator] holds `cosec:approve` on the document and is
     * not its author, and approves the [candidate] digest it has seen; a change whose candidate is another is a 409.
     * The approval covers the change's escalations, and the change is `READY`.
     *
     * An approval is a person's ([ChangeAuthority.approvalRefusal]): an API key never approves, and only a platform
     * principal approves a boundary change. A break-glass approval needs a REQUIRED change listener, and is recorded
     * there once the digest is checked, before the approval is (RC1-30).
     */
    suspend fun approve(operator: Operator, id: ChangeId, candidate: String): ChangeRequest {
        val change = flow.visible(operator, id)
        authority.require(operator, change.key, ControlActions.APPROVE)
        PublishRules.requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        authority.requireApprovable(operator, change, candidate)
        val breakGlass = change.escalations.any { it.reason == BREAK_GLASS }
        if (breakGlass) {
            PublishRules.requireAudit(true, listeners.hasRequired)
            flow.audit(ChangeEvent.Kind.BREAK_GLASS_APPROVED, change, operator.actor)
        }
        return flow.transition(id, setOf(ChangeState.AWAITING_APPROVAL)) { current ->
            if (current.candidate != candidate) {
                throw ChangeRejectedException(CONFLICT, "change ${id.value} is not candidate $candidate")
            }
            // Who approved, as they were: publish checks again that they still hold cosec:approve (review K3).
            val approval = Approval(
                operator.actor,
                clock.instant(),
                candidate,
                current.escalations.map { it.subject },
                operator.memberships,
                // Every attribute, of every type (round-2 review R2, RC1-2).
                operator.attributes,
                principalBindings = operator.principalBindings,
                breakGlass = operator.breakGlass,
                credentialId = operator.security?.principal?.credential?.id,
                issuedAt = operator.security?.principal?.issuedAt,
            )
            current.copy(
                state = ChangeState.READY,
                approval = approval,
                findings = current.findings.filterNot {
                    it.check == APPROVAL
                }
            )
        }.also { if (!breakGlass) flow.audit(ChangeEvent.Kind.APPROVED, it, operator.actor) }
    }

    /**
     * Rejects an `AWAITING_APPROVAL` change: [operator] holds `cosec:approve` on the document, and is a platform
     * principal when the change sets a boundary (`BDY-2`). Its slot is released.
     */
    suspend fun reject(operator: Operator, id: ChangeId, reason: String): ChangeRequest {
        requireRequest(reason.isNotBlank()) { "A rejection needs a reason." }
        val change = flow.visible(operator, id)
        authority.require(operator, change.key, ControlActions.APPROVE)
        authority.requireBoundaryOperator(operator, change)
        PublishRules.requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        val rejected = flow.transition(id, setOf(ChangeState.AWAITING_APPROVAL)) {
            it.copy(state = ChangeState.REJECTED, findings = it.findings + Finding(APPROVAL, "$", reason, FORBIDDEN))
        }
        slots.release(rejected)
        flow.audit(ChangeEvent.Kind.REJECTED, rejected, operator.actor)
        return rejected
    }

    /**
     * Abandons a change that is not finished; its slot is released. Its author may, and so may whoever holds
     * `cosec:publish` on the document, which the change may be holding up; a change that sets a boundary, only a
     * platform principal (`BDY-2`). A revoke of a break-glass binding that still grants, only its author (RC1-51): the
     * principal it revokes holds `cosec:publish` through that binding, and nobody else needs to, since a new revoke
     * takes its slot (`BRK-9`).
     *
     * Every abandon is recorded ([ChangeEvent.Kind.ABANDONED], its note naming the change's author). A break-glass
     * change's needs a REQUIRED change listener and is recorded there before it is stored, as its approval is (RC1-30);
     * any other change's is recorded after, best effort.
     */
    suspend fun abandon(operator: Operator, id: ChangeId): ChangeRequest {
        val current = flow.visible(operator, id)
        authority.requireBoundaryOperator(operator, current)
        if (operator.actor != current.author) {
            authority.require(operator, current.key, ControlActions.PUBLISH)
            if (preemption.revokesBreakGlass(current, revisions.head(current.key))) {
                throw ChangeRejectedException(FORBIDDEN, "only its author abandons change ${id.value}, a live revoke")
            }
        }
        val breakGlass = current.kind == ProvenanceKind.BREAK_GLASS
        if (breakGlass) {
            PublishRules.requireState(current, OPEN)
            PublishRules.requireAudit(true, listeners.hasRequired)
            flow.audit(ChangeEvent.Kind.ABANDONED, current.abandoned(), operator.actor, gated = true)
        }
        val change = flow.transition(id, OPEN) { it.copy(state = ChangeState.ABANDONED) }
        slots.release(change)
        if (!breakGlass) {
            flow.audit(ChangeEvent.Kind.ABANDONED, change.abandoned(), operator.actor)
        }
        return change
    }

    /**
     * Takes [change]'s document's staging slot for [operator]; a holder that no longer stages (finished, expired, gone)
     * frees it, and so does a `READY` holder whose revision the chain shows written, its publish's answer lost: it is
     * settled as that publish would have been, then the slot is free (review L1). A slot another change still holds is
     * a 409, unless [change] may preempt it ([Preemption.preempts]); a revoke of a binding that still grants, only
     * another such revoke preempts (reviews L3, N1):
     * the holder is first made `CONFLICTED`, from whichever staged state it is in by then (approved or past its window
     * meanwhile), and only then does the slot move and its candidate go (RC1-12). A holder that cannot be marked keeps
     * its slot, and the preemption is given up (review M3): no change that still stages ever loses its slot.
     */
    private suspend fun takeSlot(operator: Operator, change: ChangeRequest, head: Head?) {
        val conflicted = ArrayList<ChangeRequest>()
        var taken = false
        try {
            repeat(MAX_ATTEMPTS) {
                val current = store.stage(change.key)
                val holder = current?.value?.holder
                if (holder == change.id) {
                    taken = true
                    return
                }
                val held = holder?.let { store.change(it)?.value }?.takeIf(slots::stillStaged)
                if (held != null) {
                    val own = PublishRules.ownRevision(
                        revisions,
                        held,
                        head
                    )?.takeIf { held.state == ChangeState.READY }
                    if (own != null) {
                        publishing.republished(held, own, operator, taking = true)
                        return@repeat
                    }
                    // A revoke of a binding that still grants is taken only by another such revoke (review N1).
                    val guarded = preemption.revokesBreakGlass(held, head) && !preemption.revokesBreakGlass(change, head)
                    if (guarded || !preemption.preempts(operator, change, head)) {
                        throw ChangeRejectedException(CONFLICT, "[${change.key}] is staged by change ${held.id.value}")
                    }
                    conflicted += conflict(held, change)
                }
                if (store.putStage(change.key, current?.digest, StageSlot(change.id, clock.instant()))) {
                    taken = true
                    return
                }
            }
            throw ChangeRejectedException(CONFLICT, "the staging slot of [${change.key}] kept changing")
        } finally {
            withContext(NonCancellable) { conflicted.forEach { preempted(it, change, operator, taken) } }
        }
    }

    /** [held] made `CONFLICTED` for [by] from any staged state, before its slot moves; 409 or 503 when it cannot be. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun conflict(held: ChangeRequest, by: ChangeRequest): ChangeRequest = try {
        flow.transition(held.id, STAGED) { it.copy(state = ChangeState.CONFLICTED) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChangeRejectedException) {
        throw ChangeRejectedException(
            CONFLICT,
            "[${by.key}] is staged by change ${held.id.value}, which could not be preempted: ${e.message}",
            e
        )
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "change ${held.id.value} could not be preempted", e)
    }

    /**
     * [conflicted] was made `CONFLICTED` for [by], which [taken] its slot or gave up: its candidate withdrawn, and
     * recorded as a failure that says which.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun preempted(conflicted: ChangeRequest, by: ChangeRequest, operator: Operator, taken: Boolean) {
        val note = if (taken) "preempted by ${by.id.value}" else "conflicted by ${by.id.value}, which did not take the slot"
        try {
            slots.withdraw(conflicted)
            flow.audit(ChangeEvent.Kind.FAILED, conflicted.copy(note = note), operator.actor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The holder is CONFLICTED already, so it publishes nothing: only the withdrawal or its record failed.
            log.warn(e) { "Change ${conflicted.id.value} lost its slot to ${by.id.value}, unrecorded." }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
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
    suspend fun candidateOf(id: ChangeId): PolicyDocument? = publishing.candidateOf(id)
}
