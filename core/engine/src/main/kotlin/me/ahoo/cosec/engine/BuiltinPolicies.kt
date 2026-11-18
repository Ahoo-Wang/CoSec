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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand

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

    /** The statement of [ROOT], as written: what a break-glass binding grants while its window is open. */
    @InternalCoSecApi
    val ROOT_STATEMENTS: List<StatementSource> = listOf(StatementSource("root", Effect.ALLOW, listOf("*")))

    internal val root = compiler.compile(ROOT, null, ROOT_STATEMENTS)

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
