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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.decision.BindingRef

/** Receives the candidates of one request, in [CandidateOrder]. */
internal interface CandidateVisitor {
    /** [policy] applies through [via]. Returns true to stop. */
    fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean

    /** A binding that would apply to the request had it not expired. */
    fun expired(via: BindingRef.Bound)

    /** An attachment or binding names [policyId], which the snapshot does not have (or does not let it attach). */
    fun missing(policyId: String)

    /** An attachment or tenant binding names [policyId], which both the tenant document and the platform define. */
    fun ambiguous(policyId: String)
}

/**
 * The applicable policies of a request (RFC 0004 §6.4 `bindingsFor`), in the candidate order of §6.3:
 *
 * 0. the blacklist rule;
 * 1. platform `Anyone` bindings, then 2. tenant ones, by policy id;
 * 3. attachments in claim order, then principal bindings of the platform and the tenant, by binding id;
 * 4. role bindings of the app document, then of the tenant document: by the scope's roles in claim order, and within a
 *    role in document (grant) order;
 * 5. break-glass bindings of the principal, by binding id, last.
 *
 * The order never changes the decision, only which statement is reported when several match; without errors it is the
 * statement 7.x reports, as the 7.x layers are in this order. Break-glass comes last (review A2 of #1070): it bypasses a
 * *missing* ALLOW (RFC 0004 §4.8), so it reports a permit only when nothing else permits, and a request another binding
 * permits is never a use of break-glass, which would need a REQUIRED audit (RFC 0006 §9.4.3). Visits allocate nothing: the arrays are the index's own.
 * An anonymous principal has no principal bindings, break-glass bindings or attachments. One without
 * [me.ahoo.cosec.model.Subject.breakGlass] has no break-glass bindings (RFC 0009 §4.2): an API key.
 *
 * The candidates are those of the pass that reads [effect]'s statements. They are the same for both passes, except for
 * a subject without [me.ahoo.cosec.model.Subject.principalBindings], a capped credential: its attachments and principal
 * bindings are DENY candidates only. A cap narrows what the credential is granted, never what restricts its principal,
 * so a key is never more than its principal (`BND-9`, `IDN-2`, RC1-1).
 */
internal object CandidateOrder {
    fun forEach(request: FlatRequest, view: SnapshotView, effect: Effect, visitor: CandidateVisitor): Boolean {
        if (visitor.candidate(BuiltinPolicies.blacklist, BindingRef.Builtin)) {
            return true
        }
        val platform = view.platform
        val tenantDocument = view.tenant
        val subject = request.request.subject
        val tenant = request.scope.tenant.value
        val principal = if (subject.authenticated) subject.id else null
        val app = view.app
        if (visit(platform.anyone, request, platform, visitor)) {
            return true
        }
        if (tenantDocument != null && visit(tenantDocument.anyone, request, platform, visitor)) {
            return true
        }
        if (principal != null && principal(principal, request, platform, tenantDocument, effect, visitor, app)) {
            return true
        }
        if (app != null && roles(app, request, platform, visitor)) {
            return true
        }
        if (tenantDocument != null && roles(tenantDocument, request, platform, visitor, app)) {
            return true
        }
        return principal != null && subject.breakGlass &&
            visit(platform.breakGlass(tenant, principal), request, platform, visitor)
    }

    /**
     * Attachments, then the principal bindings of the platform and the tenant. For a subject without principal bindings,
     * DENY candidates only: a cap removes what is bound to the principal by name from the ALLOW pass, never its DENYs.
     */
    private fun principal(
        principal: String,
        request: FlatRequest,
        platform: CompiledDocument,
        tenant: CompiledDocument?,
        effect: Effect,
        visitor: CandidateVisitor,
        app: CompiledDocument?,
    ): Boolean {
        if (effect === Effect.ALLOW && !request.request.subject.principalBindings) {
            return false
        }
        if (attachments(request, platform, tenant, visitor)) {
            return true
        }
        val tenantId = request.scope.tenant.value
        if (visit(platform.principal(tenantId, principal), request, platform, visitor)) {
            return true
        }
        return tenant != null && visit(tenant.principal(tenantId, principal), request, platform, visitor, app)
    }

    /**
     * An id both the tenant and the platform define resolves only when both have the same content, `attachable`
     * included; otherwise it is ambiguous: preferring the tenant's would let it replace a platform DENY, preferring the
     * platform's would let it override the tenant's (RFC 0007 N2, Q15).
     */
    private fun attachments(
        request: FlatRequest,
        platform: CompiledDocument,
        tenant: CompiledDocument?,
        visitor: CandidateVisitor,
    ): Boolean {
        val attachments = request.memberships.attachments
        for (i in attachments.indices) {
            val id = attachments[i].value
            val own = tenant?.policy(id)
            val shared = platform.policy(id)
            if (own != null && shared != null && !own.sameContent(shared)) {
                visitor.ambiguous(id)
                continue
            }
            val policy = (own ?: shared)?.takeIf { it.attachable }
            if (policy == null) {
                visitor.missing(id)
            } else if (visitor.candidate(policy, policy.attached)) {
                return true
            }
        }
        return false
    }

    private fun roles(
        document: CompiledDocument,
        request: FlatRequest,
        platform: CompiledDocument,
        visitor: CandidateVisitor,
        app: CompiledDocument? = null,
    ): Boolean {
        val roles = request.scope.roles
        for (i in roles.indices) {
            if (visit(document.role(roles[i].value), request, platform, visitor, app)) {
                return true
            }
        }
        return false
    }

    /**
     * [app] resolves a tenant role binding to that app's permission policy (RFC 0007 §9.3, N1): the binding is scoped to
     * the app, so it is in scope only on a request whose view read that app's document.
     */
    private fun visit(
        entries: Array<BoundEntry>?,
        request: FlatRequest,
        platform: CompiledDocument,
        visitor: CandidateVisitor,
        app: CompiledDocument? = null,
    ): Boolean {
        if (entries == null) {
            return false
        }
        val scope = request.scope
        for (entry in entries) {
            if (!inScope(entry, scope.tenant.value, scope.app.value, scope.space.value)) {
                continue
            }
            if (request.time >= entry.expiresAt) {
                visitor.expired(entry.via)
                continue
            }
            if (entry.checksPlatform && platform.policy(entry.policyId)?.sameContent(entry.local!!) == false) {
                visitor.ambiguous(entry.policyId)
                continue
            }
            val policy = entry.local ?: platform.policy(entry.policyId)
                ?: app?.takeIf { it.owner == entry.app }?.policy(entry.policyId)
            if (policy == null) {
                visitor.missing(entry.policyId)
            } else if (visitor.candidate(policy, entry.via)) {
                return true
            }
        }
        return false
    }

    private fun inScope(entry: BoundEntry, tenant: String, app: String, space: String): Boolean =
        (entry.app == null || entry.app == app) &&
            (entry.space == null || entry.space == space) &&
            (entry.roleTenant == null || entry.roleTenant == tenant)
}
