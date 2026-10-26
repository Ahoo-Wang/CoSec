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

import me.ahoo.cosec.model.decision.BindingRef

/** Receives the candidates of one request, in [CandidateOrder]. */
internal interface CandidateVisitor {
    /** [policy] applies through [via]. Returns true to stop. */
    fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean

    /** A binding that would apply to the request had it not expired. */
    fun expired(via: BindingRef.Bound)

    /** An attachment or binding names [policyId], which the snapshot does not have (or does not let it attach). */
    fun missing(policyId: String)
}

/**
 * The applicable policies of a request (RFC 0004 §6.4 `bindingsFor`), in the candidate order of §6.3:
 *
 * 0. the blacklist rule;
 * 1. break-glass bindings of the principal, by binding id;
 * 2. platform `Anyone` bindings, then 3. tenant ones, by policy id;
 * 4. attachments in claim order, then principal bindings of the platform and the tenant, by binding id;
 * 5. role bindings of the app document, then of the tenant document: by the scope's roles in claim order, and within a
 *    role in document (grant) order.
 *
 * The order never changes the decision, only which statement is reported when several match; without errors it is the
 * statement 7.x reports, as the 7.x layers are in this order. Visits allocate nothing: the arrays are the index's own.
 * An anonymous principal has no principal bindings, break-glass bindings or attachments.
 */
internal object CandidateOrder {
    fun forEach(request: FlatRequest, view: SnapshotView, visitor: CandidateVisitor): Boolean {
        if (visitor.candidate(BuiltinPolicies.blacklist, BindingRef.Builtin)) {
            return true
        }
        val platform = view.platform
        val tenantDocument = view.tenant
        val subject = request.request.subject
        val tenant = request.scope.tenant.value
        val principal = if (subject.authenticated) subject.id else null
        if (principal != null && visit(platform.breakGlass(tenant, principal), request, platform, visitor)) {
            return true
        }
        if (visit(platform.anyone, request, platform, visitor)) {
            return true
        }
        if (tenantDocument != null && visit(tenantDocument.anyone, request, platform, visitor)) {
            return true
        }
        if (principal != null && principal(principal, request, platform, tenantDocument, visitor)) {
            return true
        }
        val app = view.app
        if (app != null && roles(app, request, platform, visitor)) {
            return true
        }
        return tenantDocument != null && roles(tenantDocument, request, platform, visitor)
    }

    /** Attachments, then the principal bindings of the platform and the tenant. */
    private fun principal(
        principal: String,
        request: FlatRequest,
        platform: CompiledDocument,
        tenant: CompiledDocument?,
        visitor: CandidateVisitor,
    ): Boolean {
        if (attachments(request, platform, tenant, visitor)) {
            return true
        }
        val tenantId = request.scope.tenant.value
        if (visit(platform.principal(tenantId, principal), request, platform, visitor)) {
            return true
        }
        return tenant != null && visit(tenant.principal(tenantId, principal), request, platform, visitor)
    }

    /** A tenant's own policy wins over a platform policy of the same id. */
    private fun attachments(
        request: FlatRequest,
        platform: CompiledDocument,
        tenant: CompiledDocument?,
        visitor: CandidateVisitor,
    ): Boolean {
        val attachments = request.memberships.attachments
        for (i in attachments.indices) {
            val id = attachments[i].value
            val policy = tenant?.attachable(id) ?: platform.attachable(id)
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
    ): Boolean {
        val roles = request.scope.roles
        for (i in roles.indices) {
            if (visit(document.role(roles[i].value), request, platform, visitor)) {
                return true
            }
        }
        return false
    }

    private fun visit(
        entries: Array<BoundEntry>?,
        request: FlatRequest,
        platform: CompiledDocument,
        visitor: CandidateVisitor,
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
            val policy = entry.local ?: platform.policy(entry.policyId)
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
