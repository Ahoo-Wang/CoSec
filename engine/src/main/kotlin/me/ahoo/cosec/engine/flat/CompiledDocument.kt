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

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.DocumentKind

/**
 * One compiled snapshot document (RFC 0004 §7.1) with its binding index (§6.4). Immutable and thread-safe; a
 * [SnapshotView] composes three of them without copying.
 *
 * Every lookup is a hash lookup, so the work per request grows with the principal's roles, attachments and applicable
 * bindings, not with the size of the document. The arrays are sorted as the candidate order needs (§6.3) once, here.
 *
 * Compiling checks only what the index relies on: unique ids, which subjects each kind of document binds, the shape of
 * break-glass bindings, that every binding can apply to some request, and references a platform document cannot leave
 * open. The checks between documents (tenant isolation, the principal binding limit) belong to the snapshot compiler
 * (RFC 0004 PR-4). A binding of an app or tenant document may name a platform policy: it is looked up per request, so a
 * platform update never recompiles them (§3 I5), and a policy the platform no longer has makes the request
 * [me.ahoo.cosec.model.decision.Cause.MissingPolicies] (§7.2). For the same reason a tenant policy whose id the
 * platform also defines is found per request, and makes it [me.ahoo.cosec.model.decision.Cause.AmbiguousPolicy]
 * unless both have the same content (RFC 0007 N2, [CompiledPolicy.sameContent]).
 */
class CompiledDocument private constructor(
    val kind: DocumentKind,
    /** The tenant id of a tenant document, the app id of an app document; empty for the platform. */
    val owner: String,
    private val policies: Map<String, CompiledPolicy>,
    bindings: List<Binding>,
) {
    /** `Anyone` bindings, by policy id, then binding id. */
    internal val anyone: Array<BoundEntry>

    /** Tenant id → principal id → break-glass bindings, by binding id. Platform only. */
    private val breakGlass: Map<String, Map<String, Array<BoundEntry>>>

    /** Tenant id → principal id → the other principal bindings, by binding id. */
    private val principals: Map<String, Map<String, Array<BoundEntry>>>

    /** Role id → role bindings, in document order (the 7.x grant order). */
    private val roles: Map<String, Array<BoundEntry>>

    init {
        val anyone = ArrayList<BoundEntry>()
        val breakGlass = HashMap<String, HashMap<String, ArrayList<BoundEntry>>>()
        val principals = HashMap<String, HashMap<String, ArrayList<BoundEntry>>>()
        val roles = LinkedHashMap<String, ArrayList<BoundEntry>>()
        val ids = HashSet<String>()
        for (binding in bindings) {
            require(ids.add(binding.id.value)) { "Duplicate binding id ${binding.id.value} in the $kind document" }
            val entry = entry(binding)
            when (val subject = binding.subject) {
                SubjectRef.Anyone -> anyone += entry
                is SubjectRef.Principal -> {
                    val index = if (binding.breakGlass) breakGlass else principals
                    index.getOrPut(subject.tenant.value, ::HashMap).getOrPut(subject.id.value, ::ArrayList) += entry
                }

                is SubjectRef.Role -> roles.getOrPut(subject.id.value, ::ArrayList) += entry
            }
        }
        this.anyone = anyone.sortedWith(BY_POLICY_THEN_ID).toTypedArray()
        this.breakGlass = breakGlass.sortedById()
        this.principals = principals.sortedById()
        this.roles = roles.mapValues { it.value.toTypedArray() }
    }

    private fun entry(binding: Binding): BoundEntry {
        // The candidate order reads role bindings from app and tenant documents and everything else from platform and
        // tenant documents (RFC 0004 §4.3, §6.3). A binding anywhere else would never be a candidate: a DENY lost.
        when (kind) {
            DocumentKind.PLATFORM -> require(binding.subject !is SubjectRef.Role) {
                "Role binding ${binding.id.value} in the platform document: role grants belong in app documents"
            }

            DocumentKind.APP -> require(binding.subject is SubjectRef.Role) {
                "Binding ${binding.id.value} of the app document $owner does not bind a role"
            }

            DocumentKind.TENANT -> Unit
        }
        val policyId = binding.policy.value
        require(policyId != BuiltinPolicies.BLACKLIST.value) { "Binding ${binding.id.value} binds the blacklist rule" }
        if (policyId == BuiltinPolicies.ROOT.value) {
            require(binding.breakGlass) { "Binding ${binding.id.value} binds $policyId without being break-glass" }
        }
        if (binding.breakGlass) {
            require(kind == DocumentKind.PLATFORM) { "Break-glass binding ${binding.id.value} in the $kind document" }
            require(binding.subject is SubjectRef.Principal) {
                "Break-glass binding ${binding.id.value} must bind a principal"
            }
            requireNotNull(binding.expiresAt) { "Break-glass binding ${binding.id.value} has no expiry" }
            // RFC 0004 §4.8, RFC 0007 §8: root is for the platform's own administrators, never a tenant's principal.
            require((binding.subject as SubjectRef.Principal).tenant == TenantId.PLATFORM) {
                "Break-glass binding ${binding.id.value} must bind a principal of the platform tenant"
            }
        }
        requireReachable(binding)
        val local = if (policyId == BuiltinPolicies.ROOT.value) BuiltinPolicies.root else policies[policyId]
        require(local != null || kind != DocumentKind.PLATFORM) {
            "Binding ${binding.id.value} of the platform document names policy $policyId, which it does not define"
        }
        return BoundEntry(
            via = BindingRef.Bound(binding.id, kind),
            policyId = policyId,
            local = local,
            // A tenant's own policy is unambiguous while the platform has no different policy of that id (RFC 0007 N2).
            checksPlatform = kind == DocumentKind.TENANT && local != null,
            app = binding.scope.app.exactApp(),
            space = binding.scope.space.exactSpace(),
            roleTenant = (binding.subject as? SubjectRef.Role)?.tenant?.value,
            expiresAt = binding.expiresAt ?: Long.MAX_VALUE,
        )
    }

    /**
     * A tenant document is read only for its own tenant and an app document only for its own app ([SnapshotView]), so
     * a binding naming another one would never be a candidate: a DENY lost.
     */
    private fun requireReachable(binding: Binding) {
        when (kind) {
            DocumentKind.PLATFORM -> Unit
            DocumentKind.APP -> require(binding.scope.app.exactApp().let { it == null || it == owner }) {
                "Binding ${binding.id.value} of the app document $owner is scoped to another app"
            }

            DocumentKind.TENANT -> {
                val tenant = when (val subject = binding.subject) {
                    is SubjectRef.Principal -> subject.tenant
                    is SubjectRef.Role -> subject.tenant
                    SubjectRef.Anyone -> null
                }
                require(tenant == null || tenant.value == owner) {
                    "Binding ${binding.id.value} of the tenant document $owner binds a subject of tenant ${tenant?.value}"
                }
            }
        }
    }

    internal fun policy(id: String): CompiledPolicy? = policies[id]

    internal fun breakGlass(tenant: String, principal: String): Array<BoundEntry>? = breakGlass[tenant]?.get(principal)

    internal fun principal(tenant: String, principal: String): Array<BoundEntry>? = principals[tenant]?.get(principal)

    internal fun role(role: String): Array<BoundEntry>? = roles[role]

    companion object {
        // Plain comparators: kotlin.comparisons is not among the packages the engine may use (EnginePurityTest).
        private val BY_ID = Comparator<BoundEntry> { a, b -> a.via.id.value.compareTo(b.via.id.value) }
        private val BY_POLICY_THEN_ID = Comparator<BoundEntry> { a, b ->
            val byPolicy = a.policyId.compareTo(b.policyId)
            if (byPolicy != 0) byPolicy else BY_ID.compare(a, b)
        }

        private fun HashMap<String, HashMap<String, ArrayList<BoundEntry>>>.sortedById() =
            mapValues { tenant -> tenant.value.mapValues { it.value.sortedWith(BY_ID).toTypedArray() } }

        private fun Match<AppId>.exactApp(): String? =
            when (this) {
                Match.Any -> null
                is Match.Exactly -> value.value
            }

        private fun Match<SpaceId>.exactSpace(): String? =
            when (this) {
                Match.Any -> null
                is Match.Exactly -> value.value
            }

        /**
         * The platform document: `Anyone` bindings (7.x global policies), break-glass bindings and the shared policies.
         * The blacklist rule and the root policy are added; every binding must name a policy defined here.
         */
        fun platform(policies: List<CompiledPolicy>, bindings: List<Binding>): CompiledDocument =
            CompiledDocument(DocumentKind.PLATFORM, "", index(DocumentKind.PLATFORM, policies), bindings)

        /** The document of [app]: its permission policies and role bindings (7.x app permissions and grants). */
        fun app(app: AppId, policies: List<CompiledPolicy>, bindings: List<Binding>): CompiledDocument =
            CompiledDocument(DocumentKind.APP, app.value, index(DocumentKind.APP, policies), bindings)

        /** The document of [tenant]: its own policies and bindings. */
        fun tenant(tenant: TenantId, policies: List<CompiledPolicy>, bindings: List<Binding>): CompiledDocument =
            CompiledDocument(DocumentKind.TENANT, tenant.value, index(DocumentKind.TENANT, policies), bindings)

        private fun index(kind: DocumentKind, policies: List<CompiledPolicy>): Map<String, CompiledPolicy> {
            val index = HashMap<String, CompiledPolicy>(policies.size * 2)
            for (policy in policies) {
                require(
                    !BuiltinPolicies.isBuiltin(policy.id)
                ) { "The $kind document defines builtin policy ${policy.id}" }
                require(
                    index.put(policy.id, policy) == null
                ) { "Duplicate policy id ${policy.id} in the $kind document" }
            }
            return index
        }
    }
}

/** A binding as the index keeps it: its filters as plain strings, null meaning any. */
internal class BoundEntry(
    val via: BindingRef.Bound,
    val policyId: String,
    /** The policy when the binding's own document defines it; else it is looked up in the platform per request. */
    val local: CompiledPolicy?,
    /** Whether the platform must not define [policyId] for [local] to apply. */
    val checksPlatform: Boolean,
    val app: String?,
    val space: String?,
    /** For a role binding of a given tenant; null for any tenant and for other subjects. */
    val roleTenant: String?,
    /** Applies while the request time is before it. */
    val expiresAt: Long,
)
