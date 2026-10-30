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

import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.Catalog
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/** The id of a change request (RFC 0007 §5.2), a sortable id from the host's generator. */
@JvmInline
value class ChangeId(val value: String) {
    init {
        require(value.isNotEmpty() && value.none { it == ':' || it.isWhitespace() || it.isISOControl() }) {
            "A change id is not empty and has no ':', whitespace or control characters: [$value]."
        }
    }
}

/**
 * The states of a change request (RFC 0007 §6.1). Every transition is a conditional write of the request, so of two
 * concurrent ones (approve and abandon, say) only one happens.
 *
 * ```
 * DRAFT -> CHECKED -> SHADOWING -> SHADOWED -> (AWAITING_APPROVAL ->) READY -> PUBLISHED
 *            \--- skip-shadow ---/
 * SHADOWING, AWAITING_APPROVAL, READY -> CONFLICTED when the base changes; CONFLICTED -> DRAFT by rebase
 * ```
 */
enum class ChangeState {
    /** Proposed, with the findings of its last check; not checked clean. */
    DRAFT,

    /** Checked clean against its base: validation, compile, authorization and grants. */
    CHECKED,

    /** Holding the document's staging slot while its shadow window runs (RFC 0007 §6.4). */
    SHADOWING,

    SHADOWED,
    AWAITING_APPROVAL,
    READY,

    /** The base changed under it; nothing of it was published. [Changes.rebase] replays it on the new head. */
    CONFLICTED,

    PUBLISHED,
    REJECTED,
    ABANDONED,
    EXPIRED,
    ;

    val terminal: Boolean get() = this == PUBLISHED || this == REJECTED || this == ABANDONED || this == EXPIRED

    /** Whether a change in this state holds its document's staging slot (RFC 0007 §5.5). */
    val staged: Boolean get() = this == SHADOWING || this == SHADOWED || this == AWAITING_APPROVAL || this == READY
}

/**
 * An entry-level operation of a change (RFC 0007 §10.1). [ifMatch], where an operation has one, is the
 * [EntryDigest] of the entry as the author last saw it: the operation applies only if the entry is still that, so a
 * replay on a newer head (a rebase) fails instead of overwriting what someone else changed.
 */
sealed interface Operation {
    /** Adds the policy, or replaces the one of the same id. */
    class PutPolicy(val policy: PolicyDefinition, val ifMatch: String? = null) : Operation

    class RemovePolicy(val id: PolicyId, val ifMatch: String? = null) : Operation

    /** Adds the binding, or replaces the one of the same id. */
    class PutBinding(val binding: Binding, val ifMatch: String? = null) : Operation

    class RemoveBinding(val id: BindingId, val ifMatch: String? = null) : Operation

    /** Adds the rate limit rule (RFC 0006 §8.1), or replaces the one of the same id. */
    class PutRateLimit(val rule: RateLimitSource, val ifMatch: String? = null) : Operation

    class RemoveRateLimit(val id: String, val ifMatch: String? = null) : Operation

    /** An app document's catalog; `null` removes it. */
    class PutCatalog(val catalog: Catalog?) : Operation

    /** The platform document's condition limits (RFC 0007 §3.1 N3); `null` returns to the defaults. */
    class SetLimits(val limits: ConditionLimits?) : Operation

    /** The external attributes the platform document declares (RFC 0007 §3.1 N3); `null` removes the section. */
    class SetAttributes(val attributes: List<Attribute>?) : Operation

    /**
     * The platform boundary (RFC 0007 §7.6): in the platform document the default, in a tenant document the one that
     * holds that tenant instead; `null` removes it.
     */
    class SetBoundary(val boundary: PolicyId?) : Operation
}

/** How bad a finding is: an error keeps the change from being checked clean, a warning is shown at publish. */
enum class Severity { ERROR, WARNING }

/**
 * What a check found (RFC 0007 §6.2, §6.3), at a JSON [path] of the candidate. [check] names the check (`V1`–`V5`,
 * `COMPILE`, `IF_MATCH`, `OPERATION`, `REFERENCES`); [status] is the HTTP status the control API answers it with
 * (RFC 0007 §10.2).
 */
data class Finding(
    val check: String,
    val path: String,
    val message: String,
    val status: Int,
    val severity: Severity = Severity.ERROR,
)

/** The head a change was made on: its revision and digest; revision 0 and no digest for a document not yet published. */
data class Base(val revision: Long, val digest: SnapshotDigest?) {
    init {
        require(
            revision >= 0 && (revision == 0L) == (digest == null)
        ) { "Revision 0 is the only base without a digest." }
    }

    companion object {
        val NONE: Base = Base(0, null)
    }
}

/**
 * A change request (RFC 0007 §6.1): a base and the entry-level operations on it, with what the last check found.
 *
 * [candidate] is the digest of the candidate the last clean check accepted ([CandidateDigest]); approvals and shadow
 * reports are bound to it, so anything that changes the candidate voids them. [checkedWith] are the heads of the other
 * documents the candidate was compiled and analyzed with; publishing checks again on the current heads, and once it has
 * written, looks whether one of them moved in between (RC1-33).
 */
data class ChangeRequest(
    val id: ChangeId,
    val key: SnapshotKey,
    val base: Base,
    val operations: List<Operation>,
    val author: Actor,
    val note: String,
    val kind: ProvenanceKind,
    val state: ChangeState,
    val findings: List<Finding>,
    val candidate: String?,
    val checkedWith: Map<SnapshotKey, SnapshotDigest?>,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Why the shadow window was skipped (`cosec:skip-shadow`, RFC 0007 Q1); `null` when it was not. */
    val skipShadow: String? = null,
    /** The revision it published; set once [state] is [ChangeState.PUBLISHED]. */
    val publishedRevision: Long? = null,
    /** What needs approval before it publishes (RFC 0007 §7.5): its author's last check found these. */
    val escalations: List<Escalation> = emptyList(),
    /** The approval it has; `null` until one is given, and again after anything it was given for changes. */
    val approval: Approval? = null,
    /** Its shadow window (RFC 0007 §6.4): `null` until it stages, and when the window was skipped. */
    val shadow: ShadowState? = null,
    /** What its author confirmed (RFC 0007 §9.5): `deny-nomatch` statements, by digest. */
    val confirmations: List<Confirmation> = emptyList(),
) {
    val errors: List<Finding> get() = findings.filter { it.severity == Severity.ERROR }
}

/** Where a document's staging slot is (RFC 0007 §5.5): the change holding it, `null` when it is free. */
/**
 * What one person cannot publish alone (RFC 0007 §7.5): a granted fragment the grant analysis could not prove contained
 * ([reason] `NOT_COVERED`, `DENY_OVERLAP`, `CROSS_TENANT`, `UNRESOLVED`), a change to a document it does not analyze
 * (`NOT_ANALYZED`), with each tenant binding it widens for an app document (`TENANT_BINDING`, RC1-34), or a
 * break-glass binding (`BREAK_GLASS`, RFC 0007 §8). [subject] names the fragment or entry; an approval covers the
 * escalations whose subjects it lists.
 */
data class Escalation(val subject: String, val reason: String, val detail: String = "")

/**
 * An approval (RFC 0007 §7.5): by [approver], at [at], of the change whose candidate digest was [candidate], covering
 * the escalation subjects [covers]. It is valid for `cosec.control.approval.expire-after` from [at], and only while
 * the approver, with the [memberships] and [attributes] they approved with, still holds `cosec:approve` on the
 * document: publishing checks that again (review K3). An approval without them cannot be checked, so it does not count.
 */
data class Approval(
    val approver: Actor,
    val at: Instant,
    val candidate: String,
    val covers: List<String>,
    val memberships: Memberships? = null,
    /**
     * The approver's token attributes as authenticated, of every JSON type (round-2 review R2): strings, numbers,
     * booleans, lists and objects, so that the check at publish reads what the approval was decided on.
     */
    val attributes: Map<String, Any> = emptyMap(),
    /**
     * Whether the approver's principal bindings reached it when it approved: false with a capped credential (RFC 0009
     * §4.2). Publish checks the approver again under the same terms, so a capped key never counts with more.
     */
    val principalBindings: Boolean = true,
    /** Whether break-glass could reach the approver when it approved: only with CoSec's own token. */
    val breakGlass: Boolean = true,
)

/**
 * A change's shadow window (RFC 0007 §6.4): [window] names it in the candidate and in every instance's report; [closed]
 * is what the reports summed to when it closed, and stays as the evidence the publisher acknowledges.
 */
data class ShadowState(
    val window: String,
    val startedAt: Instant,
    val closed: ShadowSummary? = null,
    /**
     * Every instance the control plane has seen report on the window, kept with the change where no data-plane
     * credential writes: one that reported once must report until the window closes (round-2 review).
     */
    val reporters: Set<String> = emptySet(),
)

/**
 * The reports of a window summed over the instances (RFC 0007 §6.4): counts by outcome (`SAME`, `WIDENED`, ...), when
 * it closed, whether it closed for lack of samples ([insufficient]), and whether an instance could not compile or
 * evaluate the candidate ([blocked]): such a change never publishes.
 */
data class ShadowSummary(
    val counts: Map<String, Long>,
    val closedAt: Instant,
    val insufficient: Boolean,
    val blocked: Boolean
) {
    /** Whether publishing needs the publisher to acknowledge these numbers: anything changed, or too few samples. */
    val needsAck: Boolean get() = insufficient || ACKNOWLEDGED.any { (counts[it] ?: 0) > 0 }

    private companion object {
        val ACKNOWLEDGED = listOf("WIDENED", "NARROWED", "BECAME_INDETERMINATE")
    }
}

/** What a publisher acknowledges (RFC 0007 §6.4): the window and the counts they saw, which must be the summary's. */
data class ShadowAck(val window: String, val counts: Map<String, Long>)

data class StageSlot(val holder: ChangeId?, val since: Instant?) {
    companion object {
        val FREE: StageSlot = StageSlot(null, null)
    }
}
