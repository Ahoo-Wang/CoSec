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
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.grant.GrantAnalyzer
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.ProtectedEntry
import me.ahoo.cosec.control.lifecycle.ControlTargets.boundaryOf
import me.ahoo.cosec.control.lifecycle.ControlTargets.entry
import me.ahoo.cosec.control.lifecycle.ControlTargets.scopeOf
import me.ahoo.cosec.control.lifecycle.ControlTargets.section
import me.ahoo.cosec.control.lifecycle.EscalationAnalysis.Companion.NOT_ANALYZED
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.SubjectRevocation
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Who may do what to a change (RFC 0007 §7.2–§7.5), for [Changes]. Every step is checked in order, and anything but a
 * permit stops it before it writes:
 *
 * 1. the hard rule ([TenantIsolation]): a 403 that no policy can lift;
 * 2. each target with the [authorizer]: `cosec:write` on each entry an operation touches, `cosec:grant` on the scope
 *    of each binding it puts and of each role that comes to derive a binding it did not, `cosec:unprotect` on each protected entry it changes, and the step's own action on the
 *    document. A deny is a 403, an indeterminate decision or an error a 503;
 * 3. for a candidate that checks clean, the grant analysis ([GrantAnalyzer]) of what it grants, as the operator, against
 *    the current heads. What it cannot prove contained is an [Escalation]: approval, never "probably fine" (GRT-2).
 *    Removing and narrowing grant nothing and need no approval; an analysis that fails is a 503.
 *
 * An operator holding `cosec:approve` on the document is the trust root (§7.3): its changes are not analyzed, unless
 * [ChangeOptions.fourEyes] is on. A new or changed break-glass binding always needs a second person (§8).
 */
// B4: the change authority splits by responsibility
@Suppress("TooManyFunctions")
@InternalCoSecApi
class ChangeAuthority(
    private val authorizer: ControlAuthorizer,
    private val options: ChangeOptions = ChangeOptions(),
    private val analyzer: GrantAnalyzer = GrantAnalyzer(),
    private val controlApp: AppId = PipelineControlAuthorizer.CONTROL_APP,
    /** The host's token revocations: an approver's revoked token no longer approves (RC1-11). */
    private val revocations: RevocationReader = RevocationReader.NONE,
    /** `cosec.authentication.revocation.skew`, as the host's token authenticator reads revocations with. */
    private val revocationSkew: java.time.Duration = SubjectRevocation.DEFAULT_SKEW,
) {
    private val analyst = EscalationAnalysis(analyzer, controlApp)
    private val approvals = ApprovalRules(options, revocations, revocationSkew, ::decideAs)

    /** The hard rule: a 403 when [operator] may not act on [key] at all. */
    fun isolate(operator: Operator, key: SnapshotKey) {
        if (!TenantIsolation.allows(operator, key)) {
            // The document is not named: a refusal must not tell another tenant which documents exist (review K2).
            throw ChangeRejectedException(FORBIDDEN, "$operator may not act on this document")
        }
    }

    /** [action] on [key] itself, after the hard rule. */
    suspend fun require(operator: Operator, key: SnapshotKey, action: Action) {
        isolate(operator, key)
        require(operator, action, ControlResources.document(key))
    }

    /**
     * The decision on [action] over [key] for [operator], after the hard rule (a deny when it does not let them act
     * on [key]); an error is indeterminate. For the steps that answer "cannot tell" apart from "no".
     */
    suspend fun decide(operator: Operator, key: SnapshotKey, action: Action): ControlDecision {
        if (!TenantIsolation.allows(operator, key)) {
            return ControlDecision.DENY
        }
        return decide(operator, action, ControlResources.document(key))
    }

    /** Whether [operator] holds [action] on [key]; only a permit is true, and the hard rule comes first. */
    suspend fun holds(operator: Operator, key: SnapshotKey, action: Action): Boolean =
        TenantIsolation.allows(operator, key) && decide(operator, action, ControlResources.document(key)) == ControlDecision.PERMIT

    /**
     * Every target of [operations] on [key] (RFC 0007 §7.1), after the hard rule. [breakGlass] says whether they create
     * or alter a break-glass binding ([BreakGlassRules.touches]): whatever the change's kind, that needs
     * `cosec:breakglass` on the document (review K1).
     */
    suspend fun authorize(
        operator: Operator,
        key: SnapshotKey,
        operations: List<Operation>,
        kind: ProvenanceKind = ProvenanceKind.PUBLISH,
        breakGlass: Boolean = kind == ProvenanceKind.BREAK_GLASS,
        /** The document the operations apply to, which says what a role already derives. */
        base: PolicyDocument? = null,
    ) {
        isolate(operator, key)
        // One confirmation of the control documents for all the decisions of this change, not one for each (#1218).
        authorizer.batch { via ->
            if (kind == ProvenanceKind.BREAK_GLASS) {
                // RFC 0007 §8: requesting and revoking break-glass is the platform's, by cosec:breakglass alone. Such a
                // change carries break-glass bindings only, or it never checks clean (BreakGlassRules).
                require(via, operator, ControlActions.BREAKGLASS, ControlResources.document(key))
                return@batch
            }
            if (breakGlass) {
                require(via, operator, ControlActions.BREAKGLASS, ControlResources.document(key))
            }
            for (operation in operations) {
                authorizeOperation(via, operator, key, operation)
            }
            for (binding in derivedAnew(key, operations, base)) {
                require(via, operator, ControlActions.GRANT, scopeOf(key, binding))
            }
        }
    }

    private suspend fun authorizeOperation(
        via: ControlAuthorizer,
        operator: Operator,
        key: SnapshotKey,
        operation: Operation,
    ) {
        suspend fun need(action: Action, resource: Resource) = require(via, operator, action, resource)
        when (operation) {
            is Operation.PutPolicy -> need(ControlActions.WRITE, entry(key, POLICY, operation.policy.id.value))
            is Operation.RemovePolicy -> need(ControlActions.WRITE, entry(key, POLICY, operation.id.value))
            is Operation.PutBinding -> {
                need(ControlActions.WRITE, entry(key, BINDING, operation.binding.id.value))
                need(ControlActions.GRANT, scopeOf(key, operation.binding))
            }

            is Operation.RemoveBinding -> need(ControlActions.WRITE, entry(key, BINDING, operation.id.value))
            is Operation.PutRateLimit -> need(ControlActions.WRITE, entry(key, RATE_LIMIT, operation.rule.id))
            is Operation.RemoveRateLimit -> need(ControlActions.WRITE, entry(key, RATE_LIMIT, operation.id))
            is Operation.PutRole -> need(ControlActions.WRITE, entry(key, ROLE, operation.role.id.value))
            is Operation.RemoveRole -> need(ControlActions.WRITE, entry(key, ROLE, operation.id.value))
            is Operation.PutCatalog -> need(ControlActions.WRITE, section(key, "catalog"))
            is Operation.SetLimits -> need(ControlActions.WRITE, section(key, "limits"))
            is Operation.SetAttributes -> need(ControlActions.WRITE, section(key, "attributes"))
            is Operation.SetBoundary -> boundary(via, operator, key)
        }
    }

    /**
     * For each role of the candidate [operations] make of [base], a binding it derives that [base]'s role of its id does
     * not (RFC 0012 §7.2), whichever operation makes it derive one: the role itself, or the catalog or the permission
     * policy a `"*"` role takes its permissions from. Two changes must not reach what one is refused. A role has one
     * scope, so one is enough to check `cosec:grant` on it. A binding of the same id in another space is a new binding:
     * the space is what `cosec:grant` is about. Operations that do not apply make no candidate, which never publishes.
     */
    private fun derivedAnew(key: SnapshotKey, operations: List<Operation>, base: PolicyDocument?): List<Binding> {
        if (operations.none { it is Operation.PutRole || it is Operation.PutCatalog || it is Operation.PutPolicy }) {
            return emptyList()
        }
        val candidate = Operations.apply(key, base, operations).candidate ?: return emptyList()
        val (kind, owner) = ownerOf(key)
        val catalogBefore = RoleExpansion.definedInCatalog(base)
        val catalogAfter = RoleExpansion.definedInCatalog(candidate)
        val before = base?.roles.orEmpty().associateBy { it.id }
        return candidate.roles.mapNotNull { role ->
            val held = before[role.id]?.let { RoleExpansion.bindings(kind, owner, it, catalogBefore) }.orEmpty()
                .mapTo(HashSet()) { it.id to it.scope.space }
            RoleExpansion.bindings(kind, owner, role, catalogAfter).firstOrNull { (it.id to it.scope.space) !in held }
        }
    }

    /** A change that sets a tenant's boundary is the platform's (`BDY-2`): only a platform principal ends it. */
    fun requireBoundaryOperator(operator: Operator, change: ChangeRequest) {
        if (!operator.platform && change.operations.any { it is Operation.SetBoundary }) {
            throw ChangeRejectedException(FORBIDDEN, "only a platform principal ends a boundary change")
        }
    }

    /**
     * `cosec:boundary` on [key]'s boundary. A tenant's boundary is the platform's ceiling on it (RFC 0007 §9.1): never a
     * tenant principal's to move, whatever it holds (RC1-10).
     */
    private suspend fun boundary(via: ControlAuthorizer, operator: Operator, key: SnapshotKey) {
        if (!operator.platform) {
            throw ChangeRejectedException(FORBIDDEN, "only a platform principal sets a tenant's boundary")
        }
        require(via, operator, ControlActions.BOUNDARY, boundaryOf(key))
    }

    /** [ApprovalRules.requireApprovable]: that [operator] may approve [change]'s [candidate]. */
    fun requireApprovable(operator: Operator, change: ChangeRequest, candidate: String) =
        approvals.requireApprovable(operator, change, candidate)

    /** [ApprovalRules.requireAuthor]: that [operator] is [change]'s author, who alone [does] it. */
    fun requireAuthor(operator: Operator, change: ChangeRequest, does: String) =
        approvals.requireAuthor(operator, change, does)

    /** [ApprovalRules.approvalProblem]: why [change]'s approval does not let [publisher] publish it now. */
    fun approvalProblem(change: ChangeRequest, escalations: List<Escalation>, publisher: Operator, now: Instant) =
        approvals.approvalProblem(change, escalations, publisher, now)

    /** [ApprovalRules.approverProblem]: why [change]'s approver no longer counts when [publisher] publishes it. */
    suspend fun approverProblem(change: ChangeRequest, escalations: List<Escalation>, publisher: Operator): String? =
        approvals.approverProblem(change, escalations, publisher)

    /**
     * What [operator] cannot publish alone when [candidate] replaces [base] as [key] (RFC 0007 §7.4, §7.5), read
     * against the current heads and back-references [documents] gives. Also requires `cosec:unprotect` on every
     * protected entry it changes.
     */
    suspend fun escalations(
        operator: Operator,
        key: SnapshotKey,
        operations: List<Operation>,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        documents: ChangeChecks.Context,
        now: Instant,
    ): List<Escalation> {
        val escalations = ArrayList<Escalation>()
        analyst.breakGlass(base, candidate, escalations)
        // The trust root is the platform's administrators (RFC 0007 §7.3): a tenant principal holding cosec:approve on
        // its own document approves others' changes, and its own are analyzed (RC1-10). A person, never an API key.
        val trustRoot = operator.platform && !approvals.apiKey(operator) && holds(operator, key, ControlActions.APPROVE)
        if (trustRoot && !options.fourEyes) {
            return escalations
        }
        if (key !is SnapshotKey.Tenant) {
            return escalations + analyst.untenanted(key, operations, base, candidate, documents)
        }
        // A tenant's rate limit rules need no second person, tightened or loosened: they count in the tenant's own
        // buckets (OBL-2), so they never spend another document's, and every platform and app rule still applies to
        // the tenant's requests. Rate limits are obligations on a permit, never a permit: `cosec:write` on the rule is
        // enough. The shadow window does not see a change that only touches obligations; it compares decisions.
        if (operations.any { it is Operation.SetBoundary }) {
            // Removing or replacing a boundary can widen every permit of the tenant; the analysis does not see it.
            escalations += Escalation("boundary of $key", NOT_ANALYZED, "only the trust root changes it alone")
        }
        val analysis = analyst.analyze(operator, key, base, candidate, documents, now)
        authorizer.batch { via ->
            for (entry in analysis.unprotect) {
                val resource = when (entry) {
                    is ProtectedEntry.Policy -> entry(key, POLICY, entry.id.value)
                    is ProtectedEntry.Binding -> entry(key, BINDING, entry.id.value)
                }
                require(via, operator, ControlActions.UNPROTECT, resource)
            }
        }
        analysis.verdicts.forEach { (fragment, verdict) ->
            if (verdict is GrantVerdict.Unknown) {
                escalations += Escalation(fragment.toString(), verdict.reason.name, verdict.toString())
            }
        }
        return escalations
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun decideAs(
        publisher: Operator,
        principal: AuthenticatedPrincipal,
        memberships: Memberships,
        action: Action,
        resource: Resource,
    ): ControlDecision = try {
        authorizer.decideAs(publisher, principal, memberships, action, resource)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) {
            "${action.namespace}:${action.verb} on ${resource.type}/${resource.id} as recorded failed - indeterminate."
        }
        ControlDecision.INDETERMINATE
    }

    private suspend fun require(operator: Operator, action: Action, resource: Resource) =
        require(authorizer, operator, action, resource)

    private suspend fun require(
        via: ControlAuthorizer,
        operator: Operator,
        action: Action,
        resource: Resource
    ) {
        when (decide(via, operator, action, resource)) {
            ControlDecision.PERMIT -> Unit
            ControlDecision.DENY -> throw ChangeRejectedException(
                FORBIDDEN,
                "$operator may not ${action.namespace}:${action.verb} ${resource.type}/${resource.id}"
            )

            ControlDecision.INDETERMINATE -> throw ChangeRejectedException(
                UNAVAILABLE,
                "${action.namespace}:${action.verb} on ${resource.type}/${resource.id} could not be decided"
            )
        }
    }

    private suspend fun decide(operator: Operator, action: Action, resource: Resource): ControlDecision =
        decide(authorizer, operator, action, resource)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun decide(
        via: ControlAuthorizer,
        operator: Operator,
        action: Action,
        resource: Resource
    ): ControlDecision = try {
        via.decide(operator, action, resource)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "${action.namespace}:${action.verb} on ${resource.type}/${resource.id} failed - indeterminate." }
        ControlDecision.INDETERMINATE
    }

    internal companion object {
        private val log = KotlinLogging.logger {}

        const val FORBIDDEN = 403
        const val UNAVAILABLE = 503
        const val POLICY = "policy"
        const val BINDING = "binding"
        const val RATE_LIMIT = "rateLimit"
        const val ROLE = "role"
    }
}
