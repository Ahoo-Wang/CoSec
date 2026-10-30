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

package me.ahoo.cosec.control.change

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeAuditException
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.audit.MovedHead
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.document.ShadowWindow
import me.ahoo.cosec.runtime.shadow.InMemoryShadowReports
import me.ahoo.cosec.runtime.shadow.ShadowReportReader
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/** A change operation the control API refuses, with the HTTP [status] it answers (RFC 0007 §10.2). */
class ChangeRejectedException(
    val status: Int,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

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
 *   publishing, abandoning or expiring releases it. A slot whose holder is gone or finished is free.
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
    private val authority: ChangeAuthority = ChangeAuthority(authorizer, options),
    /** What the instances report of the shadow windows (RFC 0007 §6.4); none by default. */
    reports: ShadowReportReader = InMemoryShadowReports(clock),
    /** Who hears of every step (RFC 0007 §10.5); publishing and break-glass wait for the REQUIRED ones. */
    private val listeners: ChangeListeners = ChangeListeners.NONE,
) {
    private val windows = ShadowWindows(reports, options.shadow)

    private val context = object : ChangeChecks.Context {
        override suspend fun head(key: SnapshotKey): Head? = revisions.head(key)

        override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> =
            revisions.referrers(target, policy)

        override suspend fun apps(): Versioned<AppList>? = store.apps()
    }

    private val movedHeads = MovedHeads(store, authority, context, clock)

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
        authority.authorize(author, key, operations, kind, breakGlass || kind == ProvenanceKind.BREAK_GLASS)
        requireAudit(breakGlass)
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
        val checked = checked(draft, head, author)
        if (breakGlass) {
            audit(ChangeEvent.Kind.BREAK_GLASS_REQUESTED, checked, author.actor)
        }
        if (!store.putChange(null, checked)) {
            throw ChangeRejectedException(CONFLICT, "change ${checked.id.value} already exists")
        }
        audit(
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
        val (kind, owner) = Revisions.ownerOf(key)
        require(desired.kind == kind && desired.owner == owner) { "The desired document is not a document of [$key]." }
        return propose(author, key, head?.revision ?: 0, Operations.diff(key, head?.document, desired, mode), note)
    }

    /**
     * Rollback (RFC 0007 §5.6): a change that makes [key] what it was at [revision], checked like any other: every
     * break-glass rule keys on what it does (a rollback that would bring a break-glass binding back never checks clean),
     * and so do the reserved names, the grant analysis, the approval and the shadow window. The change shows the old
     * revision's entries, and whether a revision exists is history, so [author] needs `cosec:read` on the document
     * before either is looked at (round-2 review R5).
     */
    suspend fun rollback(author: Operator, key: SnapshotKey, revision: Long, note: String): ChangeRequest {
        authority.require(author, key, ControlActions.READ)
        val head = revisions.head(key) ?: throw ChangeRejectedException(NOT_FOUND, "[$key] has no document")
        val body = revisions.revision(key, revision)
            ?: throw ChangeRejectedException(GONE, "[$key] keeps no revision $revision")
        val operations = Operations.diff(key, head.document, body, ApplyMode.REPLACE)
        return proposeAs(author, key, head.revision, operations, note, ProvenanceKind.ROLLBACK, emptyList())
    }

    /** The change [id]; one past its expiry is `EXPIRED` from then on. */
    suspend fun get(id: ChangeId): ChangeRequest = expired(read(id).value)

    /**
     * The change [id] as [operator] may see it (review K2): one that does not exist, is of a document the hard rule
     * keeps them from, or that they neither wrote nor may `cosec:read`, is the same 404, before its state is looked at
     * or expired.
     *
     * Whether they may read it is decided once, whatever the change is: on its document when the hard rule lets them
     * act on it, on their own tenant's document (the platform's, for the platform) otherwise, so that a missing change
     * costs one authorization too. When that decision is indeterminate, or the change cannot be read, the answer is a
     * 503, never a 404 (round-2 review R6): "cannot tell" is not "does not exist", and, the same for every change, it
     * tells nothing of which exist. What the decision cannot equalize is the store read of a change that exists.
     */
    suspend fun visible(operator: Operator, id: ChangeId): ChangeRequest {
        val reachable = stored(id)?.takeIf { TenantIsolation.allows(operator, it.key) }
        if (reachable != null && reachable.author == operator.actor) {
            return expired(reachable)
        }
        val key = reachable?.key ?: if (operator.platform) SnapshotKey.Platform else SnapshotKey.Tenant(operator.tenant.value)
        val decision = authority.decide(operator, key, ControlActions.READ)
        if (decision == ControlDecision.PERMIT && reachable != null) {
            return expired(reachable)
        }
        throw if (decision == ControlDecision.INDETERMINATE) {
            ChangeRejectedException(UNAVAILABLE, "whether change ${id.value} may be read could not be decided")
        } else {
            ChangeRejectedException(NOT_FOUND, "no change ${id.value}")
        }
    }

    /** The stored change [id]; a store that cannot be read is a 503, never "no change". */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun stored(id: ChangeId): ChangeRequest? = try {
        store.change(id)?.value
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "change ${id.value} could not be read", e)
    }

    /** Whether [change] creates or alters a break-glass binding of its document's current head. */
    private suspend fun touchesBreakGlass(change: ChangeRequest): Boolean =
        BreakGlassRules.touches(change.operations, revisions.head(change.key)?.document, clock.instant())

    /**
     * Replays a `CONFLICTED` change (or one not staged yet: `DRAFT`, `CHECKED`) on the current head and runs every
     * check again (RFC 0007 §5.5): the candidate, its digest and its findings are new, and nothing earlier checks or
     * approvals said carries over. Only its author rebases it, and is checked again as they are now.
     */
    suspend fun rebase(author: Operator, id: ChangeId): ChangeRequest {
        val change = visible(author, id)
        requireAuthor(author, change)
        requireState(change, REBASABLE)
        authority.authorize(author, change.key, change.operations, change.kind, touchesBreakGlass(change))
        return transition(id, REBASABLE) { current ->
            val head = revisions.head(current.key)
            val base = head?.let { Base(it.revision, it.digest) } ?: Base.NONE
            checked(current.copy(base = base, skipShadow = null, approval = null, shadow = null), head, author)
        }
    }

    /**
     * Takes the document's staging slot for a `CHECKED` change and starts its shadow window (RFC 0007 §6.4): the
     * candidate goes to the `candidate:` namespace, named by the window, with the revision of its base, and the change is
     * `SHADOWING`. 409 when the slot is held; a candidate that cannot be written releases the slot (503). The slot holds
     * the document's publishing up, so [operator] needs `cosec:publish` on it.
     *
     * Creating or revoking a break-glass binding has no window (RFC 0007 Q12): such a change goes on at once, as if
     * skipped, without `cosec:skip-shadow`.
     */
    suspend fun stage(operator: Operator, id: ChangeId): ChangeRequest {
        val change = visible(operator, id)
        authority.require(operator, change.key, ControlActions.PUBLISH)
        requireState(change, setOf(ChangeState.CHECKED))
        val head = revisions.head(change.key)
        takeSlot(change)
        if (exempt(change, head)) {
            return transition(
                id,
                setOf(ChangeState.CHECKED)
            ) { it.copy(state = afterWindow(it), skipShadow = BREAK_GLASS_EXEMPT) }
        }
        val now = clock.instant()
        val state = ShadowState("${id.value}@${now.toEpochMilli()}", now)
        @Suppress("TooGenericExceptionCaught")
        try {
            val candidate = applied(change, head?.document).first!!
            val document = SnapshotDocument(
                PolicyDocumentCodec.SCHEMA,
                change.base.revision + 1,
                now,
                PolicyDocumentCodec.encode(candidate),
            )
            store.putCandidate(change.key, StoredCandidate(state.window, change.base.revision, document))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            releaseSlot(change)
            throw ChangeRejectedException(UNAVAILABLE, "the candidate of [${change.key}] could not be staged", e)
        }
        return transition(id, setOf(ChangeState.CHECKED)) { it.copy(state = ChangeState.SHADOWING, shadow = state) }
            .also { audit(ChangeEvent.Kind.SHADOW_STARTED, it, operator.actor) }
    }

    /**
     * Looks at the window of a `SHADOWING` change (RFC 0007 §6.4; the host calls it periodically). While it may not
     * close yet, the change stays as it is. Once it may, its summary is kept as the evidence the publisher acknowledges,
     * the candidate is withdrawn, and the change is `READY`, or `AWAITING_APPROVAL` when it has escalations (§7.5). A
     * head that moved during the window makes it `CONFLICTED`. Reports that cannot be read keep the window open (503).
     */
    suspend fun shadowed(id: ChangeId): ChangeRequest {
        val change = get(id)
        requireState(change, setOf(ChangeState.SHADOWING))
        val state = change.shadow ?: error("change ${id.value} is shadowing without a window")
        if (revisions.head(change.key)?.digest != change.base.digest) {
            return settle(change, ChangeState.CONFLICTED, from = setOf(ChangeState.SHADOWING))
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
            transition(id, setOf(ChangeState.SHADOWING)) { it.copy(shadow = seen) }
        }
        val closed = transition(id, setOf(ChangeState.SHADOWING)) {
            it.copy(state = afterWindow(it), shadow = seen.copy(closed = summary))
        }
        withdraw(closed)
        audit(ChangeEvent.Kind.SHADOWED, closed, closed.author)
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
        require(reason.isNotBlank()) { "Skipping the shadow window needs a reason." }
        val change = visible(operator, id)
        if (exempt(change, revisions.head(change.key))) {
            authority.require(operator, change.key, ControlActions.PUBLISH)
        } else {
            authority.require(operator, change.key, ControlActions.SKIP_SHADOW)
        }
        requireState(change, setOf(ChangeState.CHECKED))
        takeSlot(change)
        return transition(id, setOf(ChangeState.CHECKED)) { it.copy(state = afterWindow(it), skipShadow = reason) }
    }

    /**
     * Approves an `AWAITING_APPROVAL` change (RFC 0007 §7.5): [operator] holds `cosec:approve` on the document and is
     * not its author, and approves the [candidate] digest it has seen; a change whose candidate is another is a 409.
     * The approval covers the change's escalations, and the change is `READY`.
     */
    suspend fun approve(operator: Operator, id: ChangeId, candidate: String): ChangeRequest {
        val change = visible(operator, id)
        authority.require(operator, change.key, ControlActions.APPROVE)
        requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        if (operator.actor == change.author) {
            throw ChangeRejectedException(FORBIDDEN, "the author of change ${id.value} cannot approve it")
        }
        val breakGlass = change.escalations.any { it.reason == BREAK_GLASS }
        if (breakGlass) {
            audit(ChangeEvent.Kind.BREAK_GLASS_APPROVED, change, operator.actor)
        }
        return transition(id, setOf(ChangeState.AWAITING_APPROVAL)) { current ->
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
            )
            current.copy(
                state = ChangeState.READY,
                approval = approval,
                findings = current.findings.filterNot {
                    it.check == APPROVAL
                }
            )
        }.also { if (!breakGlass) audit(ChangeEvent.Kind.APPROVED, it, operator.actor) }
    }

    /** Rejects an `AWAITING_APPROVAL` change: [operator] holds `cosec:approve` on the document. Its slot is released. */
    suspend fun reject(operator: Operator, id: ChangeId, reason: String): ChangeRequest {
        require(reason.isNotBlank()) { "A rejection needs a reason." }
        val change = visible(operator, id)
        authority.require(operator, change.key, ControlActions.APPROVE)
        requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        val rejected = transition(id, setOf(ChangeState.AWAITING_APPROVAL)) {
            it.copy(state = ChangeState.REJECTED, findings = it.findings + Finding(APPROVAL, "$", reason, FORBIDDEN))
        }
        releaseSlot(rejected)
        audit(ChangeEvent.Kind.REJECTED, rejected, operator.actor)
        return rejected
    }

    /**
     * Abandons a change that is not finished; its slot is released. Its author may, and so may whoever holds
     * `cosec:publish` on the document, which the change may be holding up.
     */
    suspend fun abandon(operator: Operator, id: ChangeId): ChangeRequest {
        val current = visible(operator, id)
        if (operator.actor != current.author) {
            authority.require(operator, current.key, ControlActions.PUBLISH)
        }
        val change = transition(
            id,
            ChangeState.entries.filterNot {
                it.terminal
            }.toSet()
        ) { it.copy(state = ChangeState.ABANDONED) }
        releaseSlot(change)
        return change
    }

    /**
     * Publishes a `READY` change. The head must still be its base, and the candidate must compile against the current
     * heads; then it is written as [Revisions.publish] writes (record first, `putIfMatch` on the base digest). Anything
     * else leaves the head as it is: a moved base makes the change `CONFLICTED`, a candidate that no longer compiles
     * (another document changed under it) `DRAFT` with the findings. The slot is released either way.
     *
     * The [publisher] needs `cosec:publish` on the document and every target of the operations, now (403, 503), and
     * the grant analysis runs again as them on the current heads. When that finds escalations the change's approval
     * does not validly cover (missing, for another candidate, expired, the author's or the publisher's own), the change
     * goes back to `AWAITING_APPROVAL` with them, keeping its slot, and nothing is published.
     *
     * From the head write on, it does not respond to cancellation: the change is settled, its outcome audited and what
     * moved meanwhile recorded, unless a store or a listener fails; what moved is recorded even then. The checks and
     * the writes before the head's respond to it.
     */
    suspend fun publish(publisher: Operator, id: ChangeId, ack: ShadowAck? = null): ChangeRequest {
        val change = visible(publisher, id)
        authority.require(publisher, change.key, ControlActions.PUBLISH)
        requireState(change, setOf(ChangeState.READY))
        acknowledged(change, ack)
        authority.authorize(publisher, change.key, change.operations, change.kind, touchesBreakGlass(change))
        val head = revisions.head(change.key)
        if (head?.digest != change.base.digest) {
            return settle(change, ChangeState.CONFLICTED)
        }
        val rechecked = checked(change, head, publisher)
        if (rechecked.state != ChangeState.CHECKED || rechecked.candidate != change.candidate) {
            return settle(change, ChangeState.DRAFT, rechecked.findings)
        }
        val problem = authority.approvalProblem(change, rechecked.escalations, publisher, clock.instant())
            ?: authority.approverProblem(change, rechecked.escalations, publisher)
        problem?.let { problem ->
            return transition(id, setOf(ChangeState.READY)) {
                it.copy(
                    state = ChangeState.AWAITING_APPROVAL,
                    escalations = rechecked.escalations,
                    approval = null,
                    findings = rechecked.findings + Finding(APPROVAL, "$", problem, PRECONDITION_REQUIRED),
                )
            }
        }
        val candidate = applied(change, head?.document).first!!
        requireAudit(BreakGlassRules.touches(change.operations, head?.document, clock.instant()))
        audit(ChangeEvent.Kind.PUBLISHING, change, publisher.actor)
        var published: ChangeRequest? = null
        val outcome = revisions.publish(change.key, head, candidate, publication(change, publisher, rechecked)) {
            published = written(change, it, rechecked, head?.document, candidate, publisher)
        }
        val settled = when (outcome) {
            is PublishOutcome.Published -> return checkNotNull(published) { "[${change.key}] was written unsettled." }
            is PublishOutcome.Conflicted -> settle(change, ChangeState.CONFLICTED)
            is PublishOutcome.Referenced -> settle(change, ChangeState.DRAFT, referenced(outcome))
        }
        audit(outcomeOf(change, settled), settled, publisher.actor)
        return settled
    }

    /**
     * What follows the head write of [change] as [outcome]: [Revisions.publish] runs it without responding to
     * cancellation, so a client that goes away leaves the change settled and audited unless a store or listener fails.
     * What moved meanwhile is recorded whatever happens to the rest: the head is written either way.
     */
    private suspend fun written(
        change: ChangeRequest,
        outcome: PublishOutcome.Published,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
    ): ChangeRequest {
        var settled: ChangeRequest? = null
        try {
            settled = settle(change, ChangeState.PUBLISHED, published = outcome.revision)
            audit(outcomeOf(change, settled), settled, publisher.actor)
            return settled
        } finally {
            recordMoved(settled ?: change, rechecked, base, candidate, publisher)
        }
    }

    private fun referenced(outcome: PublishOutcome.Referenced): List<Finding> =
        outcome.referrers.map { (policy, referrers) ->
            Finding(
                "REFERENCES",
                "$.policies",
                "[${policy.value}] is referred to by ${referrers.map { it.toString() }.sorted()}",
                UNPROCESSABLE
            )
        }

    /** What moved under [published] while it was written, as an event of its own ([MovedHeads], RC1-33). */
    private suspend fun recordMoved(
        published: ChangeRequest,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
    ) = movedHeads.record(published, rechecked, base, candidate, publisher) { found ->
        val change = published.copy(escalations = found.escalations, note = found.note)
        audit(found.kind, change, publisher.actor, found.moved)
    }

    /** How a publish is recorded: who published, who approved what needed approval, and the shadow window. */
    private fun publication(change: ChangeRequest, publisher: Operator, rechecked: ChangeRequest): Publication {
        val approvedBy = listOfNotNull(
            change.approval?.approver?.takeIf { rechecked.escalations.any { !it.informational } }
        )
        val window = when {
            change.shadow != null -> ShadowWindow(change.shadow.window, skipped = false)
            change.skipShadow != null -> ShadowWindow(null, skipped = true)
            else -> null
        }
        return Publication(change.id.value, change.kind, publisher.actor, approvedBy, window)
    }

    private fun outcomeOf(change: ChangeRequest, settled: ChangeRequest): ChangeEvent.Kind = when {
        settled.state != ChangeState.PUBLISHED -> ChangeEvent.Kind.FAILED
        change.kind == ProvenanceKind.BREAK_GLASS && change.operations.all {
            it is Operation.RemoveBinding
        } -> ChangeEvent.Kind.REVOKED
        else -> ChangeEvent.Kind.PUBLISHED
    }

    /**
     * Creating or altering break-glass ([breakGlass], [BreakGlassRules.touches]) needs a REQUIRED change listener to
     * record it (RFC 0007 §8): without one, a 503, whatever the change's kind.
     */
    private fun requireAudit(breakGlass: Boolean) {
        if (breakGlass && !listeners.hasRequired) {
            throw ChangeRejectedException(
                UNAVAILABLE,
                "break-glass needs a REQUIRED change listener, and none is configured"
            )
        }
    }

    /**
     * Runs the checks of [change] on [head] and returns it `CHECKED` or `DRAFT`, with what they found; a clean
     * candidate is analyzed for what [operator] grants with it, and carries the escalations.
     */
    private suspend fun checked(change: ChangeRequest, head: Head?, operator: Operator): ChangeRequest {
        val now = clock.instant()
        val (candidate, applyFindings) = applied(change, head?.document)
        candidate
            ?: return change.copy(
                state = ChangeState.DRAFT,
                findings = applyFindings,
                candidate = null,
                checkedWith = emptyMap(),
                escalations = emptyList(),
                updatedAt = now
            )
        val recording = RecordingContext(context)
        val checkedResult = checks.check(
            change.key,
            candidate,
            change.kind,
            change.operations,
            head?.document,
            recording
        )
        val breakGlass = BreakGlassRules.findings(
            change.kind,
            change.operations,
            head?.document,
            candidate,
            now,
            options.breakGlassMaxDuration
        )
        val result = CheckResult(applyFindings + breakGlass + checkedResult.findings, checkedResult.checkedWith)
        val escalations = if (result.clean) {
            authority.escalations(operator, change.key, change.operations, head?.document, candidate, recording, now)
        } else {
            emptyList()
        }
        return change.copy(
            state = if (result.clean) ChangeState.CHECKED else ChangeState.DRAFT,
            findings = result.findings,
            candidate = if (result.clean) CandidateDigest.of(candidate) else null,
            checkedWith = result.checkedWith + recording.read.filterKeys { it != change.key && it !in result.checkedWith },
            checkedApps = recording.apps,
            escalations = escalations,
            updatedAt = now,
        )
    }

    /**
     * A change whose window closed blocked never publishes (409); one whose summary needs acknowledging publishes only
     * with an [ack] of exactly that window and those counts (428): the publisher has seen the numbers that stand.
     */
    private fun acknowledged(change: ChangeRequest, ack: ShadowAck?) {
        val state = change.shadow ?: return
        val problem = when {
            state.closed == null -> CONFLICT to "the window of change ${change.id.value} is open"
            state.closed.blocked -> CONFLICT to "an instance could not compile or evaluate the candidate of change ${change.id.value}"
            state.closed.needsAck && !matches(ack, state.window, state.closed) ->
                PRECONDITION_REQUIRED to "publishing change ${change.id.value} needs an acknowledgement of window ${state.window}: ${state.closed.counts}"
            else -> null
        }
        problem?.let { (status, message) -> throw ChangeRejectedException(status, message) }
    }

    /** Whether [ack] names [window] and exactly the counts of [summary]; zeros may be left out. */
    private fun matches(ack: ShadowAck?, window: String, summary: ShadowSummary): Boolean =
        ack != null && ack.window == window && ack.counts.filterValues { it != 0L } == summary.counts.filterValues { it != 0L }

    /**
     * Whether [change] only creates or revokes break-glass bindings (RFC 0007 Q12): such a change has no shadow window.
     * A change of kind `BREAK_GLASS` always is one.
     */
    private fun exempt(change: ChangeRequest, head: Head?): Boolean {
        if (change.kind == ProvenanceKind.BREAK_GLASS) {
            return true
        }
        val glass = head?.document?.bindings.orEmpty().filter { it.breakGlass }.map { it.id }.toSet()
        return change.operations.isNotEmpty() && change.operations.all {
            (it is Operation.PutBinding && it.binding.breakGlass) || (it is Operation.RemoveBinding && it.id in glass)
        }
    }

    /** Withdraws the candidate of [change]'s window, if it is still the staged one; a failure only leaves it to compare. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun withdraw(change: ChangeRequest) {
        val window = change.shadow?.window ?: return
        try {
            if (store.candidate(change.key)?.window == window) {
                store.putCandidate(change.key, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                e
            ) { "Withdrawing the candidate of window $window failed; instances count it as a base mismatch once it is stale." }
        }
    }

    /**
     * The candidate [id] would publish on the current head (RFC 0007 §10.3 `simulate`), or null when its operations no
     * longer apply there. Nothing is written.
     */
    suspend fun candidateOf(id: ChangeId): PolicyDocument? {
        val change = get(id)
        return applied(change, revisions.head(change.key)?.document).first
    }

    /**
     * The candidate [change]'s operations make of [base], with the `nomatch` confirmations the control plane writes
     * (RFC 0007 §9.5), and what kept them from applying or left a DENY unconfirmed.
     */
    private fun applied(change: ChangeRequest, base: PolicyDocument?): Pair<PolicyDocument?, List<Finding>> {
        val applied = Operations.apply(change.key, base, change.operations)
        val candidate = applied.candidate ?: return null to applied.findings
        val (confirmed, findings) = NomatchConfirmations.apply(
            candidate,
            base,
            change.confirmations,
            change.author,
            change.id
        )
        return confirmed to applied.findings + findings
    }

    /** Tells the listeners (RFC 0007 §10.5); a REQUIRED one that cannot record a gated step stops it (503). */
    private suspend fun audit(
        kind: ChangeEvent.Kind,
        change: ChangeRequest,
        operator: Actor,
        moved: List<MovedHead> = emptyList(),
    ) {
        try {
            listeners.publish(change.event(kind, operator, clock, moved))
        } catch (e: ChangeAuditException) {
            throw ChangeRejectedException(UNAVAILABLE, e.message.orEmpty(), e)
        }
    }

    /** Where a change goes when its window closes or is skipped (RFC 0007 §6.1). */
    private fun afterWindow(change: ChangeRequest): ChangeState =
        if (change.escalations.all { it.informational }) ChangeState.READY else ChangeState.AWAITING_APPROVAL

    private fun requireAuthor(operator: Operator, change: ChangeRequest) {
        if (operator.actor != change.author) {
            throw ChangeRejectedException(FORBIDDEN, "only the author of change ${change.id.value} rebases it")
        }
    }

    /** Moves [change] to a final or retry [state] whatever it is now, records it, and releases the slot. */
    private suspend fun settle(
        change: ChangeRequest,
        state: ChangeState,
        findings: List<Finding> = change.findings,
        published: Long? = null,
        from: Set<ChangeState> = setOf(ChangeState.READY),
    ): ChangeRequest {
        // Once the head is written the change is published, whatever happened to it meanwhile; otherwise only a
        // change still in [from] moves.
        val settled = transition(change.id, if (state == ChangeState.PUBLISHED) ChangeState.entries.toSet() else from) {
            it.copy(state = state, findings = findings, publishedRevision = published ?: it.publishedRevision)
        }
        releaseSlot(settled)
        return settled
    }

    private suspend fun read(id: ChangeId) =
        store.change(id) ?: throw ChangeRejectedException(NOT_FOUND, "no change ${id.value}")

    /** [change], `EXPIRED` once it is past [ChangeOptions.expireAfter] and not finished. */
    private suspend fun expired(change: ChangeRequest): ChangeRequest {
        if (change.state.terminal || !clock.instant().isAfter(change.createdAt.plus(options.expireAfter))) {
            return change
        }
        val expired = transition(change.id, setOf(change.state)) { it.copy(state = ChangeState.EXPIRED) }
        releaseSlot(expired)
        return expired
    }

    /** A conditional write of [change] from one of [from] to what [next] makes of it; retried while the change moves. */
    private suspend fun transition(
        id: ChangeId,
        from: Set<ChangeState>,
        next: suspend (ChangeRequest) -> ChangeRequest
    ): ChangeRequest {
        repeat(MAX_ATTEMPTS) {
            val current = read(id)
            requireState(current.value, from)
            val updated = next(current.value).copy(updatedAt = clock.instant())
            if (store.putChange(current.digest, updated)) {
                return updated
            }
        }
        throw ChangeRejectedException(CONFLICT, "change ${id.value} kept changing")
    }

    private fun requireState(change: ChangeRequest, allowed: Set<ChangeState>) {
        if (change.state !in allowed) {
            throw ChangeRejectedException(CONFLICT, "change ${change.id.value} is ${change.state}, not one of $allowed")
        }
    }

    /** Takes the slot of [change]'s document; a holder that no longer stages (finished, expired, gone) frees it. */
    private suspend fun takeSlot(change: ChangeRequest) {
        repeat(MAX_ATTEMPTS) {
            val current = store.stage(change.key)
            val holder = current?.value?.holder
            if (holder == change.id) {
                return
            }
            if (holder != null && store.change(holder)?.value?.let(::stillStaged) == true) {
                throw ChangeRejectedException(CONFLICT, "[${change.key}] is staged by change ${holder.value}")
            }
            if (store.putStage(change.key, current?.digest, StageSlot(change.id, clock.instant()))) {
                return
            }
        }
        throw ChangeRejectedException(CONFLICT, "the staging slot of [${change.key}] kept changing")
    }

    private fun stillStaged(holder: ChangeRequest): Boolean =
        holder.state.staged && !clock.instant().isAfter(holder.createdAt.plus(options.expireAfter))

    /** Releases the slot [change] holds, and withdraws its candidate. */
    private suspend fun releaseSlot(change: ChangeRequest) {
        withdraw(change)
        repeat(MAX_ATTEMPTS) {
            val current = store.stage(change.key) ?: return
            if (current.value.holder != change.id) {
                return
            }
            if (store.putStage(change.key, current.digest, StageSlot.FREE)) {
                return
            }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val GONE = 410
        const val UNPROCESSABLE = 422
        const val PRECONDITION_REQUIRED = 428
        const val UNAVAILABLE = 503
        const val BREAK_GLASS = "BREAK_GLASS"
        const val BREAK_GLASS_EXEMPT = "break-glass has no shadow window (RFC 0007 Q12)"
        const val MAX_ATTEMPTS = 16
        const val APPROVAL = "APPROVAL"
        val REBASABLE = setOf(ChangeState.CONFLICTED, ChangeState.DRAFT, ChangeState.CHECKED)
    }
}
