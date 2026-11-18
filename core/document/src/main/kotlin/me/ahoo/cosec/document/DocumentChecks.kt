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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler.Companion.PERMISSION_PREFIX
import me.ahoo.cosec.document.PolicyDocumentCompiler.Companion.permissionPrefix
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.engine.CompileError
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolver

/**
 * What a document must satisfy beyond what compiles statement by statement: the references to the platform, who binds an app's
 * permission policy, the bounds on principal bindings, the boundary rules and the declared attributes. Reports every
 * error with its JSON path, in the order [PolicyDocumentCompiler] calls them.
 */
internal class DocumentChecks(
    private val principalBindingLimit: Int,
    private val roleGrantLimit: Int,
) {
    /**
     * Fails with [DocumentCompileException] when an app or tenant [document] binds a policy that neither it nor
     * [platform] defines. The data plane does not need this (a missing policy fails the request closed); a publisher
     * checks it before writing, so that a typo is an error rather than an outage.
     */
    internal fun checkReferences(document: PolicyDocument, platform: PolicyDocument) {
        if (document.kind == DocumentKind.PLATFORM) {
            return
        }
        val expansion = RoleExpansion.expand(document, roleGrantLimit)
        val effective = expansion.document
        val defined = effective.policies.mapTo(HashSet()) { it.id.value } + platform.policies.map { it.id.value } +
            BuiltinPolicies.ROOT.value + BuiltinPolicies.CONTROL.keys.map { it.value }
        val errors = effective.bindings.withIndex()
            .filter { (_, binding) -> binding.policy.value !in defined && !binding.policy.value.startsWith(PERMISSION_PREFIX) }
            .map { (i, binding) ->
                CompileError(
                    "${expansion.bindingPath(i)}.policy",
                    "[${binding.policy.value}] is defined neither here nor in the platform"
                )
            }
        if (errors.isNotEmpty()) {
            throw DocumentCompileException(document.describe(), errors)
        }
    }

    /**
     * Who may bind an app's permission policy (`perm:<app>/...`): that app's document, defining it; and a tenant, scoped
     * to that app only (RFC 0007 §9.3, N1, Q14), so the view that evaluates the binding is the one that reads the
     * app's document.
     */
    private fun permissionError(
        document: PolicyDocument,
        binding: me.ahoo.cosec.model.Binding,
        defined: Set<String>,
        path: String
    ): CompileError? {
        val id = binding.policy.value
        if (!id.startsWith(PERMISSION_PREFIX)) {
            return null
        }
        val ownsIt = document.kind == DocumentKind.APP && id.startsWith(permissionPrefix(document.owner))
        val app = (binding.scope.app as? Match.Exactly)?.value?.value
        val tenantGrant = document.kind == DocumentKind.TENANT && app != null && id.startsWith(permissionPrefix(app))
        return when {
            document.kind == DocumentKind.TENANT && !tenantGrant ->
                CompileError(path, "[$id] is an app's permission policy: a tenant binds it only scoped to that app")
            !ownsIt && !tenantGrant -> CompileError(
                path,
                "[$id] is another app's permission policy; only that app's document binds it"
            )
            ownsIt && id !in defined -> CompileError(
                path,
                "[$id] is not defined in this document"
            )
            else -> null
        }
    }

    /**
     * The hand-written bindings of [document], against what its [effective] form defines. A derived binding is the
     * compiler's by construction ([RoleExpansion.check]): its subject is a role, and its space and permission are
     * checked where the role writes them.
     */
    internal fun checkBindings(document: PolicyDocument, effective: PolicyDocument, errors: MutableList<CompileError>) {
        val defined = effective.policies.mapTo(HashSet()) { it.id.value }
        var principals = 0
        for ((i, binding) in document.bindings.withIndex()) {
            if (binding.subject is SubjectRef.Principal) {
                principals++
            }
            permissionError(document, binding, defined, "$.bindings[$i].policy")?.let { errors += it }
            (binding.scope.space as? Match.Exactly)?.value?.value?.takeIf(ScopeResolver::isReservedSpaceId)?.let {
                errors += CompileError(
                    "$.bindings[$i].space",
                    "[$it] is reserved: space ids beginning with [${ScopeResolver.RESERVED_SPACE_PREFIX}] are CoSec's"
                )
            }
            if (binding.policy == BuiltinPolicies.PLATFORM_ADMIN && !platformTenant(document)) {
                // It holds cosec:approve and cosec:boundary: bound in a tenant's own document, its subjects would be
                // the trust root of that document and move its boundary (RC1-36, TNT-2). The platform tenant's own
                // document binds it to the platform's principals, as `cosec-migrate bootstrap` writes it.
                errors += CompileError(
                    "$.bindings[$i].policy",
                    "[${BuiltinPolicies.PLATFORM_ADMIN.value}] is bound only by the platform and the platform tenant"
                )
            }
        }
        if (principals > principalBindingLimit) {
            errors += CompileError(
                "$.bindings",
                "$principals principal bindings, more than $principalBindingLimit: grant users through tokens or a " +
                    "membership provider",
            )
        }
    }

    /** Whether [document] is the platform's or the platform tenant's own, whose subjects are the platform's. */
    private fun platformTenant(document: PolicyDocument): Boolean = document.kind == DocumentKind.PLATFORM ||
        (document.kind == DocumentKind.TENANT && document.owner == TenantId.PLATFORM.value)

    internal fun checkBoundaries(document: PolicyDocument, errors: MutableList<CompileError>) {
        val boundaries = HashSet<String>()
        for ((i, policy) in document.policies.withIndex()) {
            if (policy.boundary) {
                boundaries += policy.id.value
                checkBoundaryPolicy(document.kind, "$.policies[$i]", policy, errors)
            }
        }
        for ((i, binding) in document.bindings.withIndex()) {
            if (binding.policy.value in boundaries) {
                errors += CompileError("$.bindings[$i].policy", "[${binding.policy.value}] is a boundary; boundaries are not bound")
            }
        }
        val boundary = document.boundary ?: return
        when {
            document.kind == DocumentKind.PLATFORM && boundary.value !in boundaries ->
                errors += CompileError("$.boundaries.default", "[${boundary.value}] is not a boundary of this document")

            document.kind == DocumentKind.TENANT && document.owner == TenantId.PLATFORM.value ->
                errors += CompileError("$.boundary", "the platform tenant has no boundary")
        }
    }

    /** A boundary policy (RFC 0007 §7.6): in the platform, never attached, only ALLOWs, at most 64 of them. */
    private fun checkBoundaryPolicy(
        kind: DocumentKind,
        path: String,
        policy: PolicyDefinition,
        errors: MutableList<CompileError>
    ) {
        if (kind != DocumentKind.PLATFORM) {
            errors += CompileError("$path.boundary", "only the platform document holds boundaries")
        }
        if (policy.attachable) {
            errors += CompileError("$path.attachable", "a boundary cannot be attached")
        }
        policy.statements.withIndex().filter { it.value.effect == Effect.DENY }.forEach { (s, _) ->
            errors += CompileError("$path.statements[$s].effect", "a boundary only allows")
        }
        if (policy.statements.size > CompiledDocument.MAX_BOUNDARY_STATEMENTS) {
            errors += CompileError(
                "$path.statements",
                "${policy.statements.size} statements, more than ${CompiledDocument.MAX_BOUNDARY_STATEMENTS}",
            )
        }
    }

    /** Each external attribute is declared once (RFC 0007 §3.1 N3). */
    internal fun checkAttributes(document: PolicyDocument, errors: MutableList<CompileError>) {
        val paths = HashSet<String>()
        document.attributes?.forEachIndexed { i, attribute ->
            if (!paths.add(attribute.path)) {
                errors += CompileError("$.attributes[$i].path", "[${attribute.path}] is declared twice")
            }
        }
    }
}
