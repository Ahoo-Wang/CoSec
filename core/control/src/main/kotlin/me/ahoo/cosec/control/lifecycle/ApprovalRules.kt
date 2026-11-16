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
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.change.Approval
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.lifecycle.ChangeAuthority.Companion.FORBIDDEN
import me.ahoo.cosec.control.lifecycle.ChangeAuthority.Companion.UNAVAILABLE
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.SubjectRevocation
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Who may approve a change, and whether an approval still lets it be published (RFC 0007 §7.5, §8): a person, not the
 * author, of the platform where the change needs it, with a token not revoked since, and holding `cosec:approve` on the
 * document as recorded when it is published. It decides nothing itself: whether the recorded approver holds
 * `cosec:approve` is [decideAs], the [ChangeAuthority]'s decision.
 */
internal class ApprovalRules(
    private val options: ChangeOptions,
    /** The host's token revocations: an approver's revoked token no longer approves (RC1-11). */
    private val revocations: RevocationReader,
    /** `cosec.authentication.revocation.skew`, as the host's token authenticator reads revocations with. */
    private val revocationSkew: java.time.Duration,
    /** The decision on an action for a recorded principal, as [ChangeAuthority] makes it. */
    private val decideAs: suspend (Operator, AuthenticatedPrincipal, Memberships, Action, Resource) -> ControlDecision,
) {
    /**
     * Why [approval] no longer counts because its approver's token was revoked since (RC1-11): that token by its id, or
     * every token of the approver issued by then; `null` when neither is. As the host's token authenticator reads them,
     * degraded read included. Any other failure to read them is a 503 (review L4): the approval says nothing about the
     * approver then, so nothing is published, and the change keeps its approval for when the store answers again.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun revoked(approval: Approval): String? = try {
        val before = revocations.subjectRevokedBefore(approval.approver.tenant, approval.approver.principal)
        when {
            approval.credentialId?.let { revocations.isRevoked(it) } == true ->
                "the token the approver approved with is revoked"

            SubjectRevocation.revokes(before, approval.issuedAt, revocationSkew) -> "the approver's tokens are revoked"
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "the revocations of the approver could not be read", e)
    }

    /** That [operator] may approve [change]'s [candidate]: not its author, a person, and the digest it has now. */
    fun requireApprovable(operator: Operator, change: ChangeRequest, candidate: String) {
        val refusal = when {
            operator.actor == change.author -> FORBIDDEN to "the author of change ${change.id.value} cannot approve it"
            else -> approvalRefusal(operator, change)?.let { FORBIDDEN to it }
                ?: (CONFLICT to "change ${change.id.value} is not candidate $candidate").takeIf { change.candidate != candidate }
        }
        refusal?.let { (status, message) -> throw ChangeRejectedException(status, message) }
    }

    /** That [operator] is [change]'s author, who alone [does] it (403). */
    fun requireAuthor(operator: Operator, change: ChangeRequest, does: String) {
        if (operator.actor != change.author) {
            throw ChangeRejectedException(FORBIDDEN, "only the author of change ${change.id.value} $does it")
        }
    }

    /**
     * Why [operator] may not approve [change] whatever it holds (RFC 0007 §7.5); `null` when it may. An approval is a
     * person's: an API key, a workload's credential, never approves, as break-glass never reaches one (`BRK-8`). A
     * boundary is the platform's (§9.1): only a platform principal approves a change that sets one (RC1-10). Under
     * four-eyes, a platform principal's change is approved by another platform principal: the second person of the
     * trust root is the trust root's.
     */
    fun approvalRefusal(operator: Operator, change: ChangeRequest): String? = when {
        apiKey(operator) -> "an API key cannot approve: an approval is a person's"

        // Publishing would not count it: its revocation could not be checked (review D3).
        operator.security?.principal?.credential?.id == null ->
            "an approval names the approver's credential, and this one names none"

        !operator.platform -> approverTenantProblem(change)

        else -> null
    }

    /** Why an approver outside the platform tenant does not count for [change]; `null` when it may. */
    private fun approverTenantProblem(change: ChangeRequest): String? = when {
        change.operations.any { it is Operation.SetBoundary } -> "only a platform principal approves a boundary change"

        options.fourEyes && change.author.tenant == TenantId.PLATFORM ->
            "under four-eyes, only a platform principal approves a platform principal's change"

        else -> null
    }

    /**
     * Why [approval] does not let [publisher] publish [change] now, with [escalations]; null when it does or none needs
     * it (informational ones never do). Bound to the candidate digest, valid for [ChangeOptions.approvalExpireAfter],
     * given by neither the author nor the publisher, and covering every escalation that needs it.
     */
    fun approvalProblem(
        change: ChangeRequest,
        escalations: List<Escalation>,
        publisher: Operator,
        now: Instant
    ): String? {
        val required = escalations.filterNot { it.informational }
        if (required.isEmpty()) {
            return null
        }
        val approval = change.approval ?: return "it needs an approval"
        return when {
            approval.candidate != change.candidate -> "the approval is for another candidate"
            now.isAfter(approval.at.plus(options.approvalExpireAfter)) -> "the approval expired"
            approval.approver == change.author || approval.approver == publisher.actor ->
                "the approval is the author's or the publisher's own"
            !approval.covers.containsAll(required.map { it.subject }) -> "the approval does not cover what it now needs"
            else -> null
        }
    }

    /**
     * Why the approver of [change] no longer counts when [publisher] publishes it, with [escalations]; null when it
     * does, or none is needed (review K3). The approver is checked again as they approved (their memberships and
     * attributes), against the current documents, in [publisher]'s request: an approver who has since lost
     * `cosec:approve` on the document, or whose right cannot be decided, sends the change back for approval.
     */
    suspend fun approverProblem(change: ChangeRequest, escalations: List<Escalation>, publisher: Operator): String? {
        if (escalations.all { it.informational }) {
            return null
        }
        val approval = change.approval ?: return "it needs an approval"
        val memberships = approval.memberships ?: return "the approval does not say what its approver held"
        if (publisher.security == null) {
            return "the approver's right to approve cannot be checked again"
        }
        if (!memberships.active) {
            return "the approver is deactivated"
        }
        // An approval by a credential without an id counts for nothing: whether it was revoked cannot be checked.
        approval.credentialId ?: return "the approval does not say what credential its approver approved with"
        if (approval.approver.tenant != TenantId.PLATFORM) {
            approverTenantProblem(change)?.let { return it }
        }
        revoked(approval)?.let { return it }
        // As the approver authenticated: memberships from the provider are asked about again, so an approver it no
        // longer knows, or knows as deactivated, does not count (RFC 0009 §4.2); claimed ones stand as recorded.
        val source = if (memberships.source == Memberships.Source.PROVIDER) {
            MembershipSource.Provider.UNCAPPED
        } else {
            MembershipSource.Claims(memberships)
        }
        // Under the terms it approved with (review N3): a capped credential's principal bindings, and break-glass for
        // anything but CoSec's own token, do not count now either. The credential is rebuilt only for those two facts,
        // under a method of its own: an API key never approved (approvalRefusal).
        val principal = AuthenticatedPrincipal(
            approval.approver.principal,
            approval.approver.tenant,
            source,
            approval.attributes,
            Identity.Credential(
                if (approval.breakGlass) Identity.Method.JWT else RECORDED_METHOD,
                RECORDED_APPROVAL,
                null,
                capped = !approval.principalBindings,
            ),
            breakGlass = approval.breakGlass,
            issuedAt = approval.issuedAt,
            apiKeyCredential = false,
            // An approval is recorded only for a credential of the control plane's app: `decideAs` decides in that app.
            app = null,
            session = null,
        )
        val approver = Operator(approval.approver, memberships, approval.attributes)
        val permitted = TenantIsolation.allows(approver, change.key) &&
            decideAs(publisher, principal, memberships, ControlActions.APPROVE, ControlResources.document(change.key)) ==
            ControlDecision.PERMIT
        return if (permitted) null else "the approver no longer holds cosec:approve on the document"
    }

    /** Whether an API key authenticated [operator], by provenance or by a credential of that name (fail closed). */
    fun apiKey(operator: Operator): Boolean = operator.security?.principal?.apiKey == true

    private companion object {
        /** The id of the credential a recorded approver is checked with again: not a credential anyone presented. */
        const val RECORDED_APPROVAL = "recorded-approval"
        private val RECORDED_METHOD = Identity.Method(RECORDED_APPROVAL)
        const val CONFLICT = 409
    }
}
