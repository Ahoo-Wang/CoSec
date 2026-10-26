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

import me.ahoo.cosec.engine.Guard
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * The flat engine over bindings (RFC 0004 §12.1), on test rules that compare the request path and allocate nothing.
 *
 * `bindings` is the number of bindings in the app document. Ten of them apply to the principal: role bindings spread
 * over its `roles` roles, each to a permission policy with one DENY and one ALLOW. The rest bind roles the principal
 * does not have, so only the index sees them. The platform holds one `Anyone` binding that never matches, and the
 * tenant document is empty.
 *
 * Scenarios: `allow`, the ALLOW of the last applicable binding matches, so both passes visit every candidate; `deny`,
 * its DENY matches; `none`, an implicit deny. `guard`: `none` gives the permission policies no condition; `shared`
 * gives them one shared condition, as converted app conditions have, which the per-evaluation cache allocates for.
 *
 * Expected: throughput and B/op independent of `bindings` (the lookup is a hash, §6.4); B/op with `guard=none` only the
 * evaluator and, for `allow` and `deny`, the decision.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class FlatEngineBenchmark {
    @Param("allow", "deny", "none")
    lateinit var scenario: String

    @Param("10", "1000", "100000")
    var bindings: Int = 0

    @Param("1", "10")
    var roles: Int = 0

    @Param("none", "shared")
    lateinit var guard: String

    private lateinit var request: FlatRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val condition = if (guard == "shared") AlwaysGuard else null
        val roleIds = List(roles) { "role$it" }
        val policies = (0 until APPLICABLE).map { i ->
            CompiledPolicy(PolicyId("perm:$i"), condition, listOf(PathRule("/deny/$i", Effect.DENY), PathRule("/allow/$i", Effect.ALLOW)))
        }
        val filler = CompiledPolicy(PolicyId("perm:filler"), condition, listOf(PathRule("/filler", Effect.ALLOW)))
        val roleBindings = (0 until APPLICABLE).map { i -> role("applies$i", roleIds[i % roles], policies[i].policyId) } +
            (0 until bindings - APPLICABLE).map { i -> role("other$i", "other$i", filler.policyId) }
        val never = CompiledPolicy(PolicyId("global"), null, listOf(PathRule("/never", Effect.DENY)))
        val platform = CompiledDocument.platform(
            listOf(never),
            listOf(Binding(BindingId("g"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), never.policyId)),
        )
        val app = CompiledDocument.app(AppId("app"), policies + filler, roleBindings)
        val tenant = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList())
        val last = APPLICABLE - 1
        val path = when (scenario) {
            "allow" -> "/allow/$last"
            "deny" -> "/deny/$last"
            "none" -> "/nothing"
            else -> error("Unknown scenario $scenario")
        }
        request = request(path, roleIds)
        view = SnapshotView(platform, app, tenant)
        // Also loads the tracing evaluator, as an application that explains decisions would.
        check(evaluate().decision == CoSecFlatEngine.evaluate(request, view, TraceLevel.FULL).decision)
    }

    @Benchmark
    fun evaluate(): FlatEvaluation = CoSecFlatEngine.evaluate(request, view)

    private fun role(id: String, role: String, policy: PolicyId) =
        Binding(
            BindingId(id),
            SubjectRef.Role(null, RoleId(role)),
            BindingScope(Match.Exactly(AppId("app")), Match.Any),
            policy,
        )

    private fun request(path: String, roles: List<String>): FlatRequest {
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId.DEFAULT to roles.mapTo(LinkedHashSet(), ::RoleId)),
            emptyList(),
        )
        val subject = ScopeSubject(TenantId("acme"), authenticated = true, memberships)
        val resolution = ScopeResolver.resolve(subject, DeclaredScope("app", ""), HostScope(null))
        val authz = AuthzRequest(
            subject = Subject("user", "acme", true, emptySet(), emptySet(), emptyMap(), root = false),
            scope = Scope.declared("acme", "app", ""),
            action = Action("http", "GET"),
            resource = Resource("http", path, emptyMap()),
            environment = Environment("127.0.0.1", "request", "device", blocked = false),
        )
        return FlatRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, time = 0)
    }

    private companion object {
        const val APPLICABLE = 10
    }
}

private object AlwaysGuard : Guard {
    override fun matches(request: AuthzRequest): Boolean = true
}

private class PathRule(private val path: String, override val effect: Effect) : Rule {
    override val index: Int = if (effect == Effect.DENY) 0 else 1
    override val name: String = path
    override val id: String? = null

    override fun match(request: AuthzRequest): RuleMatch =
        if (request.resource.id == path) RuleMatch.MATCHED else RuleMatch.ACTION_MISS
}
