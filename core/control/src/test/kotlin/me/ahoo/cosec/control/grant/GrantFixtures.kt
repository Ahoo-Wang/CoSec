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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand

/** Builders for grant analysis tests. */
internal object GrantFixtures {
    val ACME = TenantId("acme")
    const val NOW = 1_000_000L

    fun allow(actions: List<String>, resources: List<String>? = null, condition: Condition? = null) =
        statement(Effect.ALLOW, actions, resources, condition)

    fun allow(action: String, resource: String? = null, condition: Condition? = null) =
        allow(listOf(action), resource?.let(::listOf), condition)

    fun deny(action: String, resource: String? = null, condition: Condition? = null) =
        statement(Effect.DENY, listOf(action), resource?.let(::listOf), condition)

    fun statement(effect: Effect, actions: List<String>, resources: List<String>?, condition: Condition?) = GrantStatement(
        effect = effect,
        actions = actions.map(ActionPattern::parse),
        resources = resources?.map { ResourcePattern.parse(it) } ?: listOf(ResourcePattern.AnyResource),
        condition = condition,
    )

    fun policy(id: String, vararg statements: GrantStatement, attachable: Boolean = false, protected: Boolean = false) =
        GrantPolicy(PolicyId(id), statements.toList(), attachable = attachable, protected = protected)

    fun scope(app: String = "*", space: String = "*") = BindingScope(match(app, ::AppId), match(space, ::SpaceId))

    private fun <T> match(value: String, of: (String) -> T): Match<T> =
        if (value == Match.WILDCARD) Match.Any else Match.Exactly(of(value))

    fun bind(
        id: String,
        subject: SubjectRef,
        policy: String,
        app: String = "*",
        space: String = "*",
        expiresAt: Long? = null,
    ) = Binding(BindingId(id), subject, scope(app, space), PolicyId(policy), expiresAt)

    fun role(id: String, tenant: TenantId? = ACME) = SubjectRef.Role(tenant, RoleId(id))

    fun principal(id: String, tenant: TenantId = ACME) = SubjectRef.Principal(tenant, PrincipalId(id))

    fun grantor(
        id: String = "gina",
        roles: Map<String, List<String>> = emptyMap(),
        attachments: List<String> = emptyList(),
        attributes: Map<String, Any> = emptyMap(),
        tenant: TenantId = ACME,
        principalBindings: Boolean = true,
    ) = Grantor(
        tenant = tenant,
        id = PrincipalId(id),
        memberships = Memberships(
            Memberships.Source.CLAIMS,
            roles.entries.associate { (space, names) -> SpaceId(space) to names.mapTo(LinkedHashSet(), ::RoleId) },
            attachments.map(::PolicyId),
        ),
        attributes = attributes,
        principalBindings = principalBindings,
    )

    fun snapshot(platform: GrantDocument = GrantDocument.EMPTY, tenant: GrantDocument = GrantDocument.EMPTY) =
        GrantSnapshot(
            platform = platform,
            tenants = mapOf(ACME to tenant),
            now = NOW,
            unreadApps = Dimension.Only(emptySet())
        )

    fun eq(attribute: String, value: String) = Condition.Eq(attribute, Literal.Str(value))

    fun eqAttr(attribute: String, other: String) = Condition.Eq(attribute, Operand.Attr(other))

    fun cidr(attribute: String, vararg blocks: String) = Condition.Cidr(attribute, blocks.map { Literal.Str(it) })

    fun and(vararg conditions: Condition) = Condition.And(conditions.toList())

    /** The built-in control-plane policies of RFC 0007 §7.3, as statements. */
    val TENANT_ADMIN = policy(
        "cosec:tenant-admin",
        allow(
            listOf(
                "read",
                "write",
                "grant",
                "publish",
                "simulate",
                "explain",
                "analyze",
                "shadow-read"
            ).map { "cosec:$it" },
            listOf("doc/tenant/{t}/**", "scope/{t}/**"),
            eqAttr("resource.var.t", "subject.tenant"),
        ),
    )
}
