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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.grant.GrantAnalyzer
import me.ahoo.cosec.control.grant.GrantChange
import me.ahoo.cosec.control.grant.GrantDocuments
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.Grantor
import me.ahoo.cosec.control.grant.ProtectedEntry
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Who may do what to a change (RFC 0007 §7.2–§7.5), for [Changes]. Every step is checked in order, and anything but a
 * permit stops it before it writes:
 *
 * 1. the hard rule ([TenantIsolation]): a 403 that no policy can lift;
 * 2. each target with the [authorizer]: `cosec:write` on each entry an operation touches, `cosec:grant` on the scope
 *    of each binding it puts, `cosec:unprotect` on each protected entry it changes, and the step's own action on the
 *    document. A deny is a 403, an indeterminate decision or an error a 503;
 * 3. for a candidate that checks clean, the grant analysis ([GrantAnalyzer]) of what it grants, as the operator, against
 *    the current heads. What it cannot prove contained is an [Escalation]: approval, never "probably fine" (GRT-2).
 *    Removing and narrowing grant nothing and need no approval; an analysis that fails is a 503.
 *
 * An operator holding `cosec:approve` on the document is the trust root (§7.3): its changes are not analyzed, unless
 * [ChangeOptions.fourEyes] is on. A new or changed break-glass binding always needs a second person (§8).
 */
class ChangeAuthority(
    private val authorizer: ControlAuthorizer,
    private val options: ChangeOptions = ChangeOptions(),
    private val analyzer: GrantAnalyzer = GrantAnalyzer(),
    private val controlApp: AppId = PipelineControlAuthorizer.CONTROL_APP,
) {
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
    ) {
        isolate(operator, key)
        if (kind == ProvenanceKind.BREAK_GLASS) {
            // RFC 0007 §8: requesting and revoking break-glass is the platform's, by cosec:breakglass alone. Such a
            // change carries break-glass bindings only, or it never checks clean (BreakGlassRules).
            require(operator, ControlActions.BREAKGLASS, ControlResources.document(key))
            return
        }
        if (breakGlass) {
            require(operator, ControlActions.BREAKGLASS, ControlResources.document(key))
        }
        for (operation in operations) {
            when (operation) {
                is Operation.PutPolicy -> require(
                    operator,
                    ControlActions.WRITE,
                    entry(key, POLICY, operation.policy.id.value)
                )
                is Operation.RemovePolicy -> require(
                    operator,
                    ControlActions.WRITE,
                    entry(key, POLICY, operation.id.value)
                )
                is Operation.PutBinding -> {
                    require(operator, ControlActions.WRITE, entry(key, BINDING, operation.binding.id.value))
                    require(operator, ControlActions.GRANT, scopeOf(key, operation.binding))
                }

                is Operation.RemoveBinding -> require(
                    operator,
                    ControlActions.WRITE,
                    entry(key, BINDING, operation.id.value)
                )
                is Operation.PutRateLimit -> require(
                    operator,
                    ControlActions.WRITE,
                    entry(key, RATE_LIMIT, operation.rule.id)
                )
                is Operation.RemoveRateLimit -> require(
                    operator,
                    ControlActions.WRITE,
                    entry(key, RATE_LIMIT, operation.id)
                )
                is Operation.PutCatalog -> require(operator, ControlActions.WRITE, section(key, "catalog"))
                is Operation.SetLimits -> require(operator, ControlActions.WRITE, section(key, "limits"))
                is Operation.SetAttributes -> require(operator, ControlActions.WRITE, section(key, "attributes"))
                is Operation.SetBoundary -> require(operator, ControlActions.BOUNDARY, boundaryOf(key))
            }
        }
    }

    /**
     * What [operator] cannot publish alone when [candidate] replaces [base] as [key] (RFC 0007 §7.4, §7.5), read
     * against the current heads [head] gives. Also requires `cosec:unprotect` on every protected entry it changes.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun escalations(
        operator: Operator,
        key: SnapshotKey,
        operations: List<Operation>,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        head: suspend (SnapshotKey) -> PolicyDocument?,
        now: Instant,
    ): List<Escalation> {
        val escalations = ArrayList<Escalation>()
        breakGlass(base, candidate, escalations)
        val trustRoot = holds(operator, key, ControlActions.APPROVE)
        if (trustRoot && !options.fourEyes) {
            return escalations
        }
        if (key !is SnapshotKey.Tenant) {
            // The analysis reads tenant documents (§7.4.1); a platform or app change by anyone but the trust root, or
            // by it under four-eyes, needs a second person for whatever it puts.
            if (operations.any { it !is Operation.RemovePolicy && it !is Operation.RemoveBinding }) {
                escalations += Escalation("document $key", NOT_ANALYZED, "only the trust root publishes it alone")
            }
            return escalations
        }
        // A tenant's rate limit rules need no second person, tightened or loosened: they count in the tenant's own
        // buckets (OBL-2), so they never spend another document's, and every platform and app rule still applies to
        // the tenant's requests. Rate limits are obligations on a permit, never a permit: `cosec:write` on the rule is
        // enough. The shadow window does not see a change that only touches obligations; it compares decisions.
        if (operations.any { it is Operation.SetBoundary }) {
            // Removing or replacing a boundary can widen every permit of the tenant; the analysis does not see it.
            escalations += Escalation("boundary of $key", NOT_ANALYZED, "only the trust root changes it alone")
        }
        val analysis = try {
            val tenant = TenantId(key.tenantId)
            val apps = (candidate.bindings.mapNotNull { (it.scope.app as? Match.Exactly)?.value } + controlApp).toSet()
            val snapshot = GrantDocuments.snapshot(
                platform = head(SnapshotKey.Platform),
                apps = apps.associateWith { head(SnapshotKey.App(it.value)) },
                tenants = mapOf(tenant to base),
                now = now.toEpochMilli(),
            )
            val grantor = Grantor(
                operator.tenant,
                operator.actor.principal,
                operator.memberships,
                operator.attributes,
                operator.principalBindings,
            )
            analyzer.analyze(grantor, snapshot, GrantChange(tenant, GrantDocuments.of(candidate)), trustRoot = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChangeRejectedException(UNAVAILABLE, "the grant analysis of [$key] failed: ${e.message}", e)
        }
        for (entry in analysis.unprotect) {
            val resource = when (entry) {
                is ProtectedEntry.Policy -> entry(key, POLICY, entry.id.value)
                is ProtectedEntry.Binding -> entry(key, BINDING, entry.id.value)
            }
            require(operator, ControlActions.UNPROTECT, resource)
        }
        analysis.verdicts.forEach { (fragment, verdict) ->
            if (verdict is GrantVerdict.Unknown) {
                escalations += Escalation(fragment.toString(), verdict.reason.name, verdict.toString())
            }
        }
        return escalations
    }

    /**
     * Why [approval] does not let [publisher] publish [change] now, needing [required]; null when it does or none is
     * needed. Bound to the candidate digest, valid for [ChangeOptions.approvalExpireAfter], given by neither the author
     * nor the publisher, and covering everything [required] names.
     */
    fun approvalProblem(change: ChangeRequest, required: List<Escalation>, publisher: Operator, now: Instant): String? {
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
     * Why the approver of [change] no longer counts when [publisher] publishes it, needing [required]; null when it
     * does, or none is needed (review K3). The approver is checked again as they approved (their memberships and
     * attributes), against the current documents, in [publisher]'s request: an approver who has since lost
     * `cosec:approve` on the document, or whose right cannot be decided, sends the change back for approval.
     */
    suspend fun approverProblem(change: ChangeRequest, required: List<Escalation>, publisher: Operator): String? {
        if (required.isEmpty()) {
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
        // As the approver authenticated: memberships from the provider are asked about again, so an approver it no
        // longer knows, or knows as deactivated, does not count (RFC 0009 §4.2); claimed ones stand as recorded.
        val source = if (memberships.source == Memberships.Source.PROVIDER) {
            MembershipSource.Provider.UNCAPPED
        } else {
            MembershipSource.Claims(memberships)
        }
        // Under the terms it approved with (review N3): a capped credential's principal bindings, and break-glass for
        // anything but CoSec's own token, do not count now either. The credential is rebuilt only for those two facts.
        val principal = AuthenticatedPrincipal(
            approval.approver.principal,
            approval.approver.tenant,
            source,
            approval.attributes,
            Identity.Credential(
                if (approval.breakGlass) Identity.Method.JWT else Identity.Method.API_KEY,
                RECORDED_APPROVAL,
                null,
                capped = !approval.principalBindings,
            ),
        )
        val approver = Operator(approval.approver, memberships, strings(approval.attributes))
        val permitted = TenantIsolation.allows(approver, change.key) &&
            decideAs(publisher, principal, memberships, ControlActions.APPROVE, ControlResources.document(change.key)) ==
            ControlDecision.PERMIT
        return if (permitted) null else "the approver no longer holds cosec:approve on the document"
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

    /** The string-valued attributes, as the grant analysis and [Operator.attributes] read them. */
    private fun strings(attributes: Map<String, Any>): Map<String, String> =
        attributes.mapNotNull { (name, value) -> (value as? String)?.let { name to it } }.toMap()

    private suspend fun require(operator: Operator, action: Action, resource: Resource) {
        when (decide(operator, action, resource)) {
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun decide(operator: Operator, action: Action, resource: Resource): ControlDecision = try {
        authorizer.decide(operator, action, resource)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "${action.namespace}:${action.verb} on ${resource.type}/${resource.id} failed - indeterminate." }
        ControlDecision.INDETERMINATE
    }

    private fun entry(key: SnapshotKey, kind: String, id: String): Resource = try {
        ControlResources.entry(key, kind, id)
    } catch (e: IllegalArgumentException) {
        throw ChangeRejectedException(FORBIDDEN, e.message.orEmpty(), e)
    }

    private fun section(key: SnapshotKey, name: String): Resource = entry(key, name, "")

    /** `doc/tenant/<t>/boundary` for a tenant's boundary, `doc/platform` for the default (RFC 0007 §7.1). */
    private fun boundaryOf(key: SnapshotKey): Resource =
        if (key is SnapshotKey.Platform) ControlResources.document(key) else section(key, "boundary")

    /** The scope a binding of [key] grants in: its own tenant's for a tenant document, its subject's otherwise. */
    private fun scopeOf(key: SnapshotKey, binding: Binding): Resource {
        val tenant = when (key) {
            is SnapshotKey.Tenant -> TenantId(key.tenantId)
            else -> when (val subject = binding.subject) {
                is SubjectRef.Principal -> subject.tenant
                is SubjectRef.Role -> subject.tenant
                SubjectRef.Anyone -> null
            }
        }
        return try {
            ControlResources.scope(tenant, binding.scope.app, binding.scope.space)
        } catch (e: IllegalArgumentException) {
            throw ChangeRejectedException(FORBIDDEN, e.message.orEmpty(), e)
        }
    }

    private fun breakGlass(base: PolicyDocument?, candidate: PolicyDocument, into: MutableList<Escalation>) {
        val before = base?.bindings.orEmpty().associateBy { it.id }
        val digest = { binding: Binding -> EntryDigest.of(binding, candidate.kind, candidate.owner) }
        for (binding in candidate.bindings.filter { it.breakGlass }) {
            val previous = before[binding.id]
            if (previous == null || digest(previous) != digest(binding)) {
                into += Escalation("break-glass binding ${binding.id.value}", BREAK_GLASS, "always two people (RFC 0007 §8)")
            }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}

        /** The id of the credential a recorded approver is checked with again: not a credential anyone presented. */
        const val RECORDED_APPROVAL = "recorded-approval"
        const val FORBIDDEN = 403
        const val UNAVAILABLE = 503
        const val POLICY = "policy"
        const val BINDING = "binding"
        const val RATE_LIMIT = "rateLimit"
        const val NOT_ANALYZED = "NOT_ANALYZED"
        const val BREAK_GLASS = "BREAK_GLASS"
    }
}
