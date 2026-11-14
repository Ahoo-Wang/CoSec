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
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import me.ahoo.cosec.model.decision.BindingRef

/**
 * A policy ready to evaluate: compiled statements (RFC 0005 §6.1) and, per effect, their candidate index (§6.2).
 * [PolicyCompiler] builds one from a document's policy.
 *
 * [condition] is the policy condition (for a permission policy, the app condition, RFC 0004 §5.1); null when there is
 * none. Within one evaluation it is evaluated at most once, and only once a statement of the policy matched its action
 * and resource, or failed matching the resource (`ALG-7`). Policies that share a condition should share the instance:
 * it then runs once for all of them. It cannot read captured variables: a capture belongs to one statement, and the
 * condition runs once for the policy, whichever statement matched.
 *
 * Each statement's [CompiledStatement.index] is its position in [statements]. A policy's statements of one effect are
 * indexed from [IndexOptions.minStatements] on; fewer are scanned in order, which costs less than a lookup.
 *
 * The index belongs to the policy rather than to its document (RFC 0005 §6.2 has one per document): the candidate
 * order (RFC 0004 §6.3) walks bindings and evaluates a policy's statements where a binding reaches it, so a lookup per
 * reached policy is the "index result ∩ applicable bindings" of §6.2 without merging the two. A platform policy that
 * app and tenant documents bind keeps one index, and updating the platform never recompiles them (RFC 0004 §3 I5).
 */
@InternalCoSecApi
class CompiledPolicy(
    id: PolicyId,
    val condition: CheckedCondition?,
    statements: List<CompiledStatement>,
    /** Whether a principal may attach it (RFC 0004 §4.4). Permission policies are not attachable. */
    val attachable: Boolean = false,
    options: IndexOptions = IndexOptions.DEFAULT,
) {
    internal val id: String = id.value
    val statements: List<CompiledStatement> = statements.toList()
    internal val denies: Array<CompiledStatement>
    internal val allows: Array<CompiledStatement>
    private val denyIndex: StatementIndex?
    private val allowIndex: StatementIndex?

    /** The external attributes [condition] reads. */
    internal val externals: Array<Attribute> =
        condition?.let { ConditionAttributes.of(it).filter(Attribute::external).distinct().toTypedArray() } ?: NO_ATTRIBUTES

    /** The glob units of its statements (`DOC-4`). */
    internal val globUnits: Int = this.statements.sumOf { it.globUnits }

    /** The request headers its condition and its statements' conditions read (`PIP-12`). */
    internal val headers: Array<String> = HeadersRead.merge(
        condition?.let { HeadersRead.of(ConditionAttributes.of(it)) } ?: NO_HEADERS,
        this.statements.map { it.headers },
    )

    /** Whether the policy reads any external attribute, in its condition or a statement's. */
    internal val readsExternal: Boolean = externals.isNotEmpty() || statements.any { it.externals.isNotEmpty() }

    /** Allocated once, so attaching the policy allocates nothing per request. */
    internal val attached: BindingRef.Attached = BindingRef.Attached(id)

    init {
        require(
            condition == null || ConditionAttributes.of(condition).none { it.kind == Attribute.Kind.RESOURCE_VAR }
        ) {
            "The condition of policy ${id.value} reads a captured variable; only a statement's condition can"
        }
        val denies = ArrayList<CompiledStatement>()
        val allows = ArrayList<CompiledStatement>()
        for ((position, statement) in statements.withIndex()) {
            require(statement.index == position) {
                "Statement ${statement.name} of policy ${id.value} is at $position, not at its index ${statement.index}"
            }
            if (statement.effect === Effect.DENY) denies += statement else allows += statement
        }
        this.denies = denies.toTypedArray()
        this.allows = allows.toTypedArray()
        denyIndex = index(this.denies, options)
        allowIndex = index(this.allows, options)
    }

    val policyId: PolicyId get() = PolicyId(id)

    /**
     * Whether [other] has the same content: the same id, attachability, condition and statements, compared part by
     * part ([CompiledStatement.sameContent]). A tenant policy and a platform policy of the same id resolve only then
     * (RFC 0007 N2, Q15); otherwise the id is ambiguous. Never a joined text: patterns may hold any separator, so two
     * different policies could join into the same one (`BND-7`, round 2 review L1).
     */
    fun sameContent(other: CompiledPolicy): Boolean {
        if (this === other) {
            return true
        }
        if (other.id != id || other.attachable != attachable) {
            return false
        }
        if (other.condition != condition || other.statements.size != statements.size) {
            return false
        }
        for (i in statements.indices) {
            if (!statements[i].sameContent(other.statements[i])) {
                return false
            }
        }
        return true
    }

    internal fun statements(effect: Effect): Array<CompiledStatement> = if (effect === Effect.DENY) denies else allows

    /** The index over [statements] of [effect], or null to scan them. */
    internal fun index(effect: Effect): StatementIndex? = if (effect === Effect.DENY) denyIndex else allowIndex

    private companion object {
        fun index(statements: Array<CompiledStatement>, options: IndexOptions): StatementIndex? =
            if (statements.isNotEmpty() && statements.size >= options.minStatements) {
                StatementIndex(statements, options)
            } else {
                null
            }
    }
}

/** The policies every platform document has (RFC 0004 §4.7, §4.8). Documents can neither define nor replace them. */
@InternalCoSecApi
object BuiltinPolicies {
    /** One DENY statement, matching a blocked request. The first candidate of every evaluation. Cannot be bound. */
    val BLACKLIST: PolicyId = PolicyId("cosec:blacklist")

    /** One ALLOW statement, matching everything. Only a break-glass binding may bind it, and it is not attachable. */
    val ROOT: PolicyId = PolicyId("cosec:root")

    private val compiler = PolicyCompiler()

    internal val blacklist = compiler.compile(
        BLACKLIST,
        null,
        listOf(
            StatementSource(
                "blacklist",
                Effect.DENY,
                listOf("*"),
                condition = Condition.Eq("environment.blocked", Literal.Bool(true)),
            ),
        ),
    )
    internal val root = compiler.compile(ROOT, null, listOf(StatementSource("root", Effect.ALLOW, listOf("*"))))

    /** Every control-plane action on every document and scope (RFC 0007 §7.3); bound by `bootstrap`, then by admins. */
    val PLATFORM_ADMIN: PolicyId = PolicyId("cosec:platform-admin")

    /**
     * A tenant's own documents and scopes, for administering them; bound by a platform administrator in the tenant. It
     * may skip the shadow window of its own tenant's document: on a tenant with little traffic the window would run its
     * whole maximum, and what a change grants is still analyzed and, beyond what the administrator holds, approved.
     */
    val TENANT_ADMIN: PolicyId = PolicyId("cosec:tenant-admin")

    /** As [TENANT_ADMIN], read-only. */
    val TENANT_VIEWER: PolicyId = PolicyId("cosec:tenant-viewer")

    private val TENANT_ADMIN_VERBS = listOf(
        "read",
        "write",
        "grant",
        "publish",
        "simulate",
        "explain",
        "analyze",
        "shadow-read"
    )
    private val TENANT_VIEWER_VERBS = listOf("read", "simulate", "explain", "analyze", "shadow-read")

    /** A statement's `{t}`, the tenant its resource names, is the caller's: what the tenant policies grant is the tenant's own. */
    private val OWN_TENANT = Condition.Eq("resource.var.t", Operand.Attr("subject.tenant"))

    /**
     * The statements of the built-in control-plane policies (RFC 0007 §7.3), as written: the grant analysis reads them
     * the way it reads any policy. Like [ROOT], every platform document has them and no document can define them.
     */
    val CONTROL: Map<PolicyId, List<StatementSource>> = mapOf(
        PLATFORM_ADMIN to listOf(
            StatementSource("platform-admin", Effect.ALLOW, listOf("cosec:*"), listOf(ResourceSource("doc/**"), ResourceSource("scope/**"))),
        ),
        TENANT_ADMIN to listOf(
            tenantStatement("tenant-admin", TENANT_ADMIN_VERBS),
            StatementSource(
                "tenant-admin-skip-shadow",
                Effect.ALLOW,
                listOf("cosec:skip-shadow"),
                listOf(ResourceSource("doc/tenant/{t}")),
                OWN_TENANT,
            ),
        ),
        TENANT_VIEWER to listOf(tenantStatement("tenant-viewer", TENANT_VIEWER_VERBS)),
    )

    internal val control: List<CompiledPolicy> = CONTROL.map { (id, statements) ->
        compiler.compile(
            id,
            null,
            statements
        )
    }

    private fun tenantStatement(name: String, verbs: List<String>) = StatementSource(
        name,
        Effect.ALLOW,
        verbs.map { "cosec:$it" },
        listOf(ResourceSource("doc/tenant/{t}/**"), ResourceSource("scope/{t}/**")),
        OWN_TENANT,
    )

    internal fun isBuiltin(id: String): Boolean = id == BLACKLIST.value || id == ROOT.value || CONTROL.keys.any {
        it.value == id
    }
}
