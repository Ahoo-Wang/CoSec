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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import java.time.Clock

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
 * The shadow window comes with RFC 0007 PR-7: until then [stage] and [shadowed] are the hooks it uses.
 */
class Changes(
    private val store: ControlStore,
    private val revisions: Revisions,
    private val ids: () -> ChangeId,
    authorizer: ControlAuthorizer,
    private val clock: Clock = Clock.systemUTC(),
    private val options: ChangeOptions = ChangeOptions(),
    private val checks: ChangeChecks = ChangeChecks(options),
    private val authority: ChangeAuthority = ChangeAuthority(authorizer, options),
) {
    private val context = object : ChangeChecks.Context {
        override suspend fun head(key: SnapshotKey): Head? = revisions.head(key)

        override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> = revisions.referrers(
            target,
            policy
        )
    }

    /**
     * Proposes [operations] on revision [base] of [key] (0 for a document not published yet) and checks them. The
     * change is `CHECKED` when every check passes, `DRAFT` with its findings otherwise. A base that is not the head is
     * a 409: the author proposes on what they have seen. The [author] must be allowed every target first (403, 503).
     */
    suspend fun propose(
        author: Operator,
        key: SnapshotKey,
        base: Long,
        operations: List<Operation>,
        note: String,
        kind: ProvenanceKind = ProvenanceKind.PUBLISH,
    ): ChangeRequest {
        authority.authorize(author, key, operations)
        val head = revisions.head(key)
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
        )
        val checked = checked(draft, head, author)
        if (!store.putChange(null, checked)) {
            throw ChangeRejectedException(CONFLICT, "change ${checked.id.value} already exists")
        }
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

    /** Rollback (RFC 0007 §5.6): a change that makes [key] what it was at [revision], checked like any other. */
    suspend fun rollback(author: Operator, key: SnapshotKey, revision: Long, note: String): ChangeRequest {
        authority.isolate(author, key)
        val head = revisions.head(key) ?: throw ChangeRejectedException(NOT_FOUND, "[$key] has no document")
        val body = revisions.revision(key, revision)
            ?: throw ChangeRejectedException(GONE, "[$key] keeps no revision $revision")
        val operations = Operations.diff(key, head.document, body, ApplyMode.REPLACE)
        return propose(author, key, head.revision, operations, note, ProvenanceKind.ROLLBACK)
    }

    /** The change [id]; one past its expiry is `EXPIRED` from then on. */
    suspend fun get(id: ChangeId): ChangeRequest = expired(read(id).value)

    /**
     * Replays a `CONFLICTED` change (or one not staged yet: `DRAFT`, `CHECKED`) on the current head and runs every
     * check again (RFC 0007 §5.5): the candidate, its digest and its findings are new, and nothing earlier checks or
     * approvals said carries over. Only its author rebases it, and is checked again as they are now.
     */
    suspend fun rebase(author: Operator, id: ChangeId): ChangeRequest {
        val change = get(id)
        requireState(change, REBASABLE)
        requireAuthor(author, change)
        authority.authorize(author, change.key, change.operations)
        return transition(id, REBASABLE) { current ->
            val head = revisions.head(current.key)
            val base = head?.let { Base(it.revision, it.digest) } ?: Base.NONE
            checked(current.copy(base = base, skipShadow = null, approval = null), head, author)
        }
    }

    /**
     * Takes the document's staging slot for a `CHECKED` change and starts its window: `SHADOWING`. 409 when held. The
     * slot holds the document's publishing up, so [operator] needs `cosec:publish` on it.
     */
    suspend fun stage(operator: Operator, id: ChangeId): ChangeRequest {
        val change = get(id)
        requireState(change, setOf(ChangeState.CHECKED))
        authority.require(operator, change.key, ControlActions.PUBLISH)
        takeSlot(change)
        return transition(id, setOf(ChangeState.CHECKED)) { it.copy(state = ChangeState.SHADOWING) }
    }

    /**
     * The window of a `SHADOWING` change closed (RFC 0007 PR-7 calls it): `SHADOWED`, then `READY`, or
     * `AWAITING_APPROVAL` when it has escalations (RFC 0007 §7.5).
     */
    suspend fun shadowed(id: ChangeId): ChangeRequest =
        transition(id, setOf(ChangeState.SHADOWING)) { it.copy(state = afterWindow(it)) }

    /**
     * Skips the window of a `CHECKED` change (`cosec:skip-shadow`, RFC 0007 Q1): it takes the slot and is `READY`. The
     * [reason] is kept on the change and written into nothing else until audit lands (RFC 0007 PR-8). [operator] needs
     * `cosec:skip-shadow` on the document; a change with escalations goes on to `AWAITING_APPROVAL`.
     */
    suspend fun skipShadow(operator: Operator, id: ChangeId, reason: String): ChangeRequest {
        require(reason.isNotBlank()) { "Skipping the shadow window needs a reason." }
        val change = get(id)
        requireState(change, setOf(ChangeState.CHECKED))
        authority.require(operator, change.key, ControlActions.SKIP_SHADOW)
        takeSlot(change)
        return transition(id, setOf(ChangeState.CHECKED)) { it.copy(state = afterWindow(it), skipShadow = reason) }
    }

    /**
     * Approves an `AWAITING_APPROVAL` change (RFC 0007 §7.5): [operator] holds `cosec:approve` on the document and is
     * not its author, and approves the [candidate] digest it has seen; a change whose candidate is another is a 409.
     * The approval covers the change's escalations, and the change is `READY`.
     */
    suspend fun approve(operator: Operator, id: ChangeId, candidate: String): ChangeRequest {
        val change = get(id)
        requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        authority.require(operator, change.key, ControlActions.APPROVE)
        if (operator.actor == change.author) {
            throw ChangeRejectedException(FORBIDDEN, "the author of change ${id.value} cannot approve it")
        }
        return transition(id, setOf(ChangeState.AWAITING_APPROVAL)) { current ->
            if (current.candidate != candidate) {
                throw ChangeRejectedException(CONFLICT, "change ${id.value} is not candidate $candidate")
            }
            val approval = Approval(operator.actor, clock.instant(), candidate, current.escalations.map { it.subject })
            current.copy(
                state = ChangeState.READY,
                approval = approval,
                findings = current.findings.filterNot {
                    it.check == APPROVAL
                }
            )
        }
    }

    /** Rejects an `AWAITING_APPROVAL` change: [operator] holds `cosec:approve` on the document. Its slot is released. */
    suspend fun reject(operator: Operator, id: ChangeId, reason: String): ChangeRequest {
        require(reason.isNotBlank()) { "A rejection needs a reason." }
        val change = get(id)
        requireState(change, setOf(ChangeState.AWAITING_APPROVAL))
        authority.require(operator, change.key, ControlActions.APPROVE)
        val rejected = transition(id, setOf(ChangeState.AWAITING_APPROVAL)) {
            it.copy(state = ChangeState.REJECTED, findings = it.findings + Finding(APPROVAL, "$", reason, FORBIDDEN))
        }
        releaseSlot(rejected)
        return rejected
    }

    /**
     * Abandons a change that is not finished; its slot is released. Its author may, and so may whoever holds
     * `cosec:publish` on the document, which the change may be holding up.
     */
    suspend fun abandon(operator: Operator, id: ChangeId): ChangeRequest {
        val current = get(id)
        authority.isolate(operator, current.key)
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
     */
    suspend fun publish(publisher: Operator, id: ChangeId): ChangeRequest {
        val change = get(id)
        requireState(change, setOf(ChangeState.READY))
        authority.require(publisher, change.key, ControlActions.PUBLISH)
        authority.authorize(publisher, change.key, change.operations)
        val head = revisions.head(change.key)
        if (head?.digest != change.base.digest) {
            return settle(change, ChangeState.CONFLICTED)
        }
        val rechecked = checked(change, head, publisher)
        if (rechecked.state != ChangeState.CHECKED || rechecked.candidate != change.candidate) {
            return settle(change, ChangeState.DRAFT, rechecked.findings)
        }
        authority.approvalProblem(change, rechecked.escalations, publisher, clock.instant())?.let { problem ->
            return transition(id, setOf(ChangeState.READY)) {
                it.copy(
                    state = ChangeState.AWAITING_APPROVAL,
                    escalations = rechecked.escalations,
                    approval = null,
                    findings = rechecked.findings + Finding(APPROVAL, "$", problem, PRECONDITION_REQUIRED),
                )
            }
        }
        val candidate = Operations.apply(change.key, head?.document, change.operations).candidate!!
        val approvedBy = listOfNotNull(change.approval?.approver?.takeIf { rechecked.escalations.isNotEmpty() })
        val publication = Publication(change.id.value, change.kind, publisher.actor, approvedBy)
        return when (val outcome = revisions.publish(change.key, head, candidate, publication)) {
            is PublishOutcome.Published -> settle(change, ChangeState.PUBLISHED, published = outcome.revision)
            is PublishOutcome.Conflicted -> settle(change, ChangeState.CONFLICTED)
            is PublishOutcome.Referenced -> settle(
                change,
                ChangeState.DRAFT,
                outcome.referrers.map { (policy, referrers) ->
                    Finding(
                        "REFERENCES",
                        "$.policies",
                        "[${policy.value}] is referred to by ${referrers.map { it.toString() }.sorted()}",
                        UNPROCESSABLE
                    )
                },
            )
        }
    }

    /**
     * Runs the checks of [change] on [head] and returns it `CHECKED` or `DRAFT`, with what they found; a clean
     * candidate is analyzed for what [operator] grants with it, and carries the escalations.
     */
    private suspend fun checked(change: ChangeRequest, head: Head?, operator: Operator): ChangeRequest {
        val now = clock.instant()
        val applied = Operations.apply(change.key, head?.document, change.operations)
        val candidate = applied.candidate
            ?: return change.copy(
                state = ChangeState.DRAFT,
                findings = applied.findings,
                candidate = null,
                checkedWith = emptyMap(),
                escalations = emptyList(),
                updatedAt = now
            )
        val result = checks.check(change.key, candidate, change.kind, change.operations, head?.document, context)
        val escalations = if (result.clean) {
            authority.escalations(operator, change.key, change.operations, head?.document, candidate, {
                revisions.head(it)?.document
            }, now)
        } else {
            emptyList()
        }
        return change.copy(
            state = if (result.clean) ChangeState.CHECKED else ChangeState.DRAFT,
            findings = result.findings,
            candidate = if (result.clean) CandidateDigest.of(candidate) else null,
            checkedWith = result.checkedWith,
            escalations = escalations,
            updatedAt = now,
        )
    }

    /** Where a change goes when its window closes or is skipped (RFC 0007 §6.1). */
    private fun afterWindow(change: ChangeRequest): ChangeState =
        if (change.escalations.isEmpty()) ChangeState.READY else ChangeState.AWAITING_APPROVAL

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
    ): ChangeRequest {
        // Once the head is written the change is published, whatever happened to it meanwhile; otherwise only a
        // change still READY moves.
        val from = if (state == ChangeState.PUBLISHED) ChangeState.entries.toSet() else setOf(ChangeState.READY)
        val settled = transition(change.id, from) {
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

    private suspend fun releaseSlot(change: ChangeRequest) {
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
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val GONE = 410
        const val UNPROCESSABLE = 422
        const val PRECONDITION_REQUIRED = 428
        const val MAX_ATTEMPTS = 16
        const val APPROVAL = "APPROVAL"
        val REBASABLE = setOf(ChangeState.CONFLICTED, ChangeState.DRAFT, ChangeState.CHECKED)
    }
}
