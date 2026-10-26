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

import me.ahoo.cosec.engine.CallLog
import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.engine.TestGuard
import me.ahoo.cosec.engine.TestRule
import me.ahoo.cosec.engine.request
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
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
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject

const val NOW = 1_000L

/** Builds policies whose matchers all write to one [log]: `guard:<policy id>` and `rule:<policy id>.<index>`. */
class FlatFixture(val log: CallLog = CallLog()) {
    fun guard(name: String, behavior: GuardBehavior = GuardBehavior.MATCH) = TestGuard(name, behavior, log)

    fun policy(
        id: String,
        vararg statements: Pair<Effect, RuleBehavior>,
        guard: TestGuard? = null,
        attachable: Boolean = false,
    ): CompiledPolicy =
        CompiledPolicy(
            PolicyId(id),
            guard,
            statements.mapIndexed { index, (effect, behavior) ->
                TestRule(
                    "$id.$index",
                    effect,
                    index,
                    null,
                    behavior,
                    log
                )
            },
            attachable,
        )
}

fun allow(behavior: RuleBehavior = RuleBehavior.MATCH) = Effect.ALLOW to behavior

fun deny(behavior: RuleBehavior = RuleBehavior.MATCH) = Effect.DENY to behavior

fun anyone(id: String, policy: String, app: String? = null, space: String? = null, expiresAt: Long? = null) =
    Binding(BindingId(id), SubjectRef.Anyone, scope(app, space), PolicyId(policy), expiresAt)

fun principal(
    id: String,
    policy: String,
    tenant: String = "acme",
    principal: String = "alice",
    expiresAt: Long? = null,
    breakGlass: Boolean = false,
) = Binding(
    BindingId(id),
    SubjectRef.Principal(TenantId(tenant), PrincipalId(principal)),
    scope(null, null),
    PolicyId(policy),
    expiresAt,
    breakGlass,
)

fun root(id: String = "root", tenant: String = "(platform)", principal: String = "cosec", expiresAt: Long = NOW + 1) =
    principal(id, BuiltinPolicies.ROOT.value, tenant, principal, expiresAt, breakGlass = true)

fun role(
    id: String,
    role: String,
    policy: String,
    app: String? = "app",
    space: String? = "",
    tenant: String? = null,
    expiresAt: Long? = null,
) = Binding(
    BindingId(id),
    SubjectRef.Role(tenant?.let(::TenantId), RoleId(role)),
    scope(app, space),
    PolicyId(policy),
    expiresAt
)

private fun scope(app: String?, space: String?) =
    BindingScope(
        app?.let { Match.Exactly(AppId(it)) } ?: Match.Any,
        space?.let { Match.Exactly(SpaceId(it)) } ?: Match.Any
    )

fun platform(vararg policies: CompiledPolicy, bindings: List<Binding> = emptyList()) =
    CompiledDocument.platform(policies.toList(), bindings)

fun app(vararg policies: CompiledPolicy, bindings: List<Binding> = emptyList(), app: String = "app") =
    CompiledDocument.app(AppId(app), policies.toList(), bindings)

fun tenant(vararg policies: CompiledPolicy, bindings: List<Binding> = emptyList(), tenant: String = "acme") =
    CompiledDocument.tenant(TenantId(tenant), policies.toList(), bindings)

/** A request resolved through [ScopeResolver]: [roles] are the principal's roles in [space]. */
fun flatRequest(
    principal: String = "alice",
    tenant: String = "acme",
    authenticated: Boolean = true,
    app: String = "app",
    space: String = "",
    roles: List<String> = emptyList(),
    attachments: List<String> = emptyList(),
    time: Long = NOW,
    blocked: Boolean = false,
): FlatRequest {
    val memberships = Memberships(
        Memberships.Source.CLAIMS,
        if (roles.isEmpty()) emptyMap() else mapOf(SpaceId(space) to roles.mapTo(LinkedHashSet(), ::RoleId)),
        attachments.map(::PolicyId),
    )
    val resolution = ScopeResolver.resolve(
        ScopeSubject(TenantId(tenant), authenticated, memberships),
        DeclaredScope(app, space),
        HostScope(null),
    )
    val scope = (resolution as ScopeResolution.Resolved).scope
    val base = request(blocked = blocked)
    val subject = Subject(principal, tenant, authenticated, emptySet(), emptySet(), emptyMap(), false)
    val authz = AuthzRequest(subject, base.scope, base.action, base.resource, base.environment)
    return FlatRequest(authz, scope, memberships, time)
}

fun bound(id: String, document: DocumentKind = DocumentKind.PLATFORM) = BindingRef.Bound(BindingId(id), document)

fun ref(policy: String, index: Int, via: BindingRef) = StatementRef.Policy(
    PolicyId(policy),
    index,
    "$policy.$index",
    via
)

val blacklistRef = StatementRef.Policy(BuiltinPolicies.BLACKLIST, 0, "blacklist", BindingRef.Builtin)
