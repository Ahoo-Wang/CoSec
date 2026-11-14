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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
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
 * (`PolicyDocumentCompiler` in `cosec-runtime`). A binding of an app or tenant document may name a platform policy: it is looked up per request, so a
 * platform update never recompiles them (§3 I5), and a policy the platform no longer has makes the request
 * [me.ahoo.cosec.model.decision.Cause.MissingPolicies] (§7.2). For the same reason a tenant policy whose id the
 * platform also defines is found per request, and makes it [me.ahoo.cosec.model.decision.Cause.AmbiguousPolicy]
 * unless both have the same content (RFC 0007 N2, [CompiledPolicy.sameContent]).
 *
 * Platform boundaries (RFC 0007 §7.6) live in the platform document, apart from its policies: a boundary policy holds
 * only ALLOW statements, at most [MAX_BOUNDARY_STATEMENTS], and no binding or attachment can reach it. The platform
 * names a default boundary, a tenant document may name another; the platform tenant has none.
 */
@InternalCoSecApi
class CompiledDocument private constructor(
    val kind: DocumentKind,
    /** The tenant id of a tenant document, the app id of an app document; empty for the platform. */
    val owner: String,
    private val policies: Map<String, CompiledPolicy>,
    bindings: List<Binding>,
    /** The platform's boundary policies, by id; empty for the other kinds. */
    private val boundaries: Map<String, CompiledPolicy> = emptyMap(),
    /**
     * The boundary the document names: the platform's default, or a tenant's own (RFC 0007 §7.6); null for none. A
     * tenant's names a policy of the platform, looked up per request.
     */
    internal val boundary: String? = null,
    /** Its rate limit rules, in document order (RFC 0006 §8.1): a permit's obligations come from them. */
    val rateLimits: List<CompiledRateLimit> = emptyList(),
    /** For a tenant document, [needsApp]'s apps; empty for the other kinds. */
    private val boundApps: Set<String> = emptySet(),
) {
    /**
     * Whether one of its policies, boundaries or rate limit rules reads an external attribute (RFC 0006 §5.4.2): only
     * then is there anything to plan. A tenant's boundary is a policy of the platform, so the platform's says (#1208).
     */
    internal val readsExternal: Boolean =
        policies.values.any { it.readsExternal } || boundaries.values.any { it.readsExternal } ||
            rateLimits.any { it.externals.isNotEmpty() }

    /**
     * The request headers any of its policies, boundaries or rate limit rules (their conditions and keys) reads, in lower
     * case. A request that sent one of them with different values cannot be decided on its value, whichever statement
     * reads it: the pipeline answers it 400 before evaluating (`PIP-12`, RC1-38), so the answer does not tell a DENY
     * from an ALLOW, nor whether the statement applied.
     */
    val headersRead: List<String> = headersOf(policies, boundaries, rateLimits)

    /** `Anyone` bindings, by policy id, then binding id. */
    internal val anyone: Array<BoundEntry>

    /** Tenant id → principal id → break-glass bindings, by binding id. Platform only. */
    private val breakGlass: Map<String, Map<String, Array<BoundEntry>>>

    /** Tenant id → principal id → the other principal bindings, by binding id. */
    private val principals: Map<String, Map<String, Array<BoundEntry>>>

    /** Role id → role bindings, in document (grant) order. */
    private val roles: Map<String, Array<BoundEntry>>

    /** The ids of the break-glass bindings. */
    private val breakGlassIds: Set<String>

    /**
     * How many policy ids several of its bindings name ([BoundEntry.repeat]): a pass evaluates each such policy once,
     * whatever the number of bindings (`ALG-9`).
     */
    internal val repeats: Int

    init {
        require(rateLimits.size <= MAX_RATE_LIMIT_RULES) {
            "The $kind document holds ${rateLimits.size} rate limit rules, more than $MAX_RATE_LIMIT_RULES"
        }
        checkGlobBudget(policies, boundaries, rateLimits)
        require(boundary == null || kind != DocumentKind.APP) { "An app document names no boundary." }
        require(!(kind == DocumentKind.TENANT && boundary != null && owner == TenantId.PLATFORM.value)) {
            "The platform tenant has no boundary"
        }
        require(boundary == null || kind != DocumentKind.PLATFORM || boundary in boundaries) {
            "The default boundary $boundary is not a boundary policy of the platform document"
        }
        if (bindings.isEmpty() && rateLimits.isEmpty()) {
            // Nothing published for the tenant or app: a view builds one of these per request, so it holds no collections.
            this.anyone = NO_ENTRIES
            this.breakGlass = emptyMap()
            this.principals = emptyMap()
            this.roles = emptyMap()
            this.breakGlassIds = emptySet()
            this.repeats = 0
        } else {
            val anyone = ArrayList<BoundEntry>()
            val breakGlass = HashMap<String, HashMap<String, ArrayList<BoundEntry>>>()
            val principals = HashMap<String, HashMap<String, ArrayList<BoundEntry>>>()
            val roles = LinkedHashMap<String, ArrayList<BoundEntry>>()
            val ruleIds = HashSet<String>()
            for (rule in rateLimits) {
                require(ruleIds.add(rule.id)) { "Duplicate rate limit rule ${rule.id} in the $kind document" }
            }
            val slots = repeatSlots(bindings)
            val ids = HashSet<String>()
            for (binding in bindings) {
                require(ids.add(binding.id.value)) { "Duplicate binding id ${binding.id.value} in the $kind document" }
                val entry = entry(binding, slots[binding.policy.value] ?: NOT_REPEATED)
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
            this.breakGlassIds = bindings.filter { it.breakGlass }.mapTo(HashSet()) { it.id.value }
            this.repeats = slots.size
        }
    }

    /**
     * A slot for each policy id several of [bindings] name, in binding order; none for an id bound once. Two bindings of
     * one document that name an id and both resolve resolve to the same policy: the platform's (a tenant's own copy of
     * it resolves to the platform's too), else the document's own, else that of the one app both are scoped to
     * ([CandidateOrder]), since one scoped to any app finds only the platform's.
     */
    private fun repeatSlots(bindings: List<Binding>): Map<String, Int> {
        if (bindings.size < 2) {
            return emptyMap()
        }
        val counts = HashMap<String, Int>(bindings.size * 2)
        for (binding in bindings) {
            counts.merge(binding.policy.value, 1, Int::plus)
        }
        val slots = HashMap<String, Int>()
        for (binding in bindings) {
            val id = binding.policy.value
            if (counts.getValue(id) > 1 && id !in slots) {
                slots[id] = slots.size
            }
        }
        return slots
    }

    /**
     * Whether [via] is a break-glass binding of this document: a permit through it must be written to a REQUIRED audit
     * (RFC 0006 §9.4.3).
     */
    fun isBreakGlass(via: BindingRef): Boolean =
        via is BindingRef.Bound && via.document == kind && via.id.value in breakGlassIds

    private fun entry(binding: Binding, repeat: Int): BoundEntry {
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
        require(policyId !in boundaries) { "Binding ${binding.id.value} binds boundary policy $policyId" }
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
            repeat = repeat,
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

    /**
     * Whether a request in [app] reads that app's document whatever its roles, because this tenant document binds one of
     * the app's permission policies to `Anyone` or to a principal (RFC 0007 §9.3, RC1-17): without it the binding could
     * not resolve. A role binding needs no such entry: it applies only with a role in the scope, and then the app's
     * document is read anyway. One set lookup, so the view key stays O(1) and the hot path does no I/O to decide it.
     */
    fun needsApp(app: String): Boolean = app in boundApps

    internal fun policy(id: String): CompiledPolicy? = policies[id]

    /** The platform's boundary policy [id], or null. */
    internal fun boundaryPolicy(id: String): CompiledPolicy? = boundaries[id]

    internal fun breakGlass(tenant: String, principal: String): Array<BoundEntry>? = breakGlass[tenant]?.get(principal)

    internal fun principal(tenant: String, principal: String): Array<BoundEntry>? = principals[tenant]?.get(principal)

    internal fun role(role: String): Array<BoundEntry>? = roles[role]

    @InternalCoSecApi
    companion object {
        private const val NOT_REPEATED = -1
        private val NO_ENTRIES = emptyArray<BoundEntry>()

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
         * [headersRead] of a document of [policies], [boundaries] and [rateLimits]. An empty document, which a view
         * builds per request for an app or tenant with no stored document, allocates nothing here.
         */
        private fun headersOf(
            policies: Map<String, CompiledPolicy>,
            boundaries: Map<String, CompiledPolicy>,
            rateLimits: List<CompiledRateLimit>,
        ): List<String> {
            if (policies.isEmpty() && boundaries.isEmpty() && rateLimits.isEmpty()) {
                return emptyList()
            }
            val names = LinkedHashSet<String>()
            policies.values.forEach { names.addAll(it.headers) }
            boundaries.values.forEach { names.addAll(it.headers) }
            for (rule in rateLimits) {
                names.addAll(rule.statement.headers)
                rule.key?.let(HeadersRead::headerOf)?.let(names::add)
            }
            return if (names.isEmpty()) emptyList() else names.toList()
        }

        /**
         * `DOC-4`: the glob units of every pattern of the document, at most [MAX_GLOB_UNITS]. A rate limit rule whose key
         * reads a captured variable counts twice: its statement is matched, then captured for the key. Free when empty.
         */
        private fun checkGlobBudget(
            policies: Map<String, CompiledPolicy>,
            boundaries: Map<String, CompiledPolicy>,
            rateLimits: List<CompiledRateLimit>,
        ) {
            if (policies.isEmpty() && boundaries.isEmpty() && rateLimits.isEmpty()) {
                return
            }
            val units = policies.values.sumOf { it.globUnits } + boundaries.values.sumOf { it.globUnits } +
                rateLimits.sumOf { it.statement.globUnits * if (it.key?.kind == Attribute.Kind.RESOURCE_VAR) 2 else 1 }
            require(units <= MAX_GLOB_UNITS) {
                "The document's path pattern segments that mix *, ? or {name} with other text hold $units tokens " +
                    "and literal characters in all, more than $MAX_GLOB_UNITS"
            }
        }

        /** The most statements a boundary policy may hold (RFC 0007 §7.6). */
        const val MAX_BOUNDARY_STATEMENTS: Int = 64

        /**
         * The most rate limit rules one document may hold (`DOC-6`, round 2 review M2). Every rule a permit matches is
         * an obligation, and the pipeline acquires each with a call to the limiter, one after the other: without a
         * bound, a document's author decides how many limiter calls every permitted request makes. Fixed, like the
         * other bounds: raising it later breaks no document, lowering it would.
         */
        const val MAX_RATE_LIMIT_RULES: Int = 64

        /**
         * The most glob units one document's path patterns hold in all (RC1-8, `DOC-4`): the tokens of every segment
         * that mixes `*`, `?` or `{name}` with other text
         * ([me.ahoo.cosec.engine.pattern.HttpPathPattern.MAX_SEGMENT_TOKENS]) and the characters of its literals, twice
         * after a leading `**` and for a rate limit rule keyed by a captured variable. Matching such a segment takes at
         * most its units times the path segment's length in steps, and the pipeline accepts no segment longer than 256
         * characters. A pass evaluates a policy once for each document that binds it, however often (`ALG-9`), so this
         * bounds what matching one document's patterns costs a request; `GlobBoundBenchmark` measures it at the bound.
         */
        const val MAX_GLOB_UNITS: Int = 2048

        /**
         * The platform document: `Anyone` bindings (policies of every principal, anonymous ones included), break-glass bindings and the shared policies.
         * The blacklist rule and the root policy are added, and so are the control-plane policies (RFC 0007 §7.3), which
         * the platform's and the tenants' bindings may name; every binding must name a policy defined here.
         * [boundaries] are its boundary policies and [defaultBoundary] the one a tenant gets unless it names another.
         */
        fun platform(
            policies: List<CompiledPolicy>,
            bindings: List<Binding>,
            rateLimits: List<CompiledRateLimit> = emptyList(),
            boundaries: List<CompiledPolicy> = emptyList(),
            defaultBoundary: String? = null,
        ): CompiledDocument {
            val index = index(DocumentKind.PLATFORM, policies)
            BuiltinPolicies.control.forEach { index[it.id] = it }
            val boundaryIndex = HashMap<String, CompiledPolicy>(boundaries.size * 2)
            for (boundary in boundaries) {
                require(boundary.id !in index && boundaryIndex.put(boundary.id, boundary) == null) {
                    "Duplicate policy id ${boundary.id} in the platform document"
                }
                require(!BuiltinPolicies.isBuiltin(boundary.id)) { "Builtin policy ${boundary.id} is not a boundary" }
                require(
                    boundary.denies.isEmpty()
                ) { "Boundary ${boundary.id} holds a DENY statement; a boundary only allows" }
                require(!boundary.attachable) { "Boundary ${boundary.id} cannot be attached" }
                require(boundary.allows.size <= MAX_BOUNDARY_STATEMENTS) {
                    "Boundary ${boundary.id} holds ${boundary.allows.size} statements, more than $MAX_BOUNDARY_STATEMENTS"
                }
            }
            return CompiledDocument(
                DocumentKind.PLATFORM,
                "",
                index,
                bindings,
                boundaryIndex,
                defaultBoundary,
                rateLimits
            )
        }

        /** The document of [app]: its permission policies and the role bindings that grant them. */
        fun app(
            app: AppId,
            policies: List<CompiledPolicy>,
            bindings: List<Binding>,
            rateLimits: List<CompiledRateLimit> = emptyList(),
        ): CompiledDocument =
            CompiledDocument(
                DocumentKind.APP,
                app.value,
                if (policies.isEmpty()) emptyMap() else index(DocumentKind.APP, policies),
                bindings,
                rateLimits = rateLimits
            )

        /**
         * The document of [tenant]: its own policies and bindings, and the platform [boundary] it is held to instead
         * of the default (only the platform writes it, RFC 0007 Q11). [boundApps] are the apps whose permission
         * policies [bindings] grant to `Anyone` or to a principal ([needsApp]): the snapshot compiler knows which
         * policies are an app's, the engine does not.
         */
        fun tenant(
            tenant: TenantId,
            policies: List<CompiledPolicy>,
            bindings: List<Binding>,
            rateLimits: List<CompiledRateLimit> = emptyList(),
            boundary: String? = null,
            boundApps: Set<String> = emptySet(),
        ): CompiledDocument =
            CompiledDocument(
                DocumentKind.TENANT,
                tenant.value,
                if (policies.isEmpty()) emptyMap() else index(DocumentKind.TENANT, policies),
                bindings,
                boundary = boundary,
                rateLimits = rateLimits,
                // Almost every tenant binds no app's permissions: the empty set answers without hashing.
                boundApps = if (boundApps.isEmpty()) emptySet() else boundApps.toHashSet(),
            )

        private fun index(kind: DocumentKind, policies: List<CompiledPolicy>): HashMap<String, CompiledPolicy> {
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
    /**
     * The slot of its policy id among the document's [CompiledDocument.repeats] when other bindings of the document name
     * it too, else -1. Such a binding is a candidate only while its pass has not evaluated the policy through
     * another one (`ALG-9`): the results depend on the request and the policy, never on the binding.
     */
    val repeat: Int,
)
