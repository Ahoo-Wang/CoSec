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
import me.ahoo.cosec.model.decision.Decision
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
 * The 8.0 side of the authorization budget (RFC 0008 §3.8, RFC 0005 Q12): the data and scenarios of 7.0's
 * `SimpleAuthorizationBenchmark` (tag `v7.0.1`, with `role-heavy` from `main`), converted as RFC 0004 §5.1 converts
 * them, and decided by compiled statements.
 *
 * - Global policies: platform policies with an `Anyone` binding. Principal policies: attachable platform policies the
 *   principal attaches. Every 7.x path action `/<p>/...` is `http:*` on `http:path:/<p>/...`.
 * - App permissions: one permission policy `perm:app/<id>` per permission granted on its own and, for the `*` grant,
 *   one with every permission statement in catalog order; role grants are role bindings of the app document.
 *
 * Each operation does what 7.0's `authorize` does for its request: it resolves the scope from the memberships, builds
 * the request and the snapshot view, parses the path and decides. 7.0 reads its repositories (`Mono.just`) and
 * rebuilds the app's role permissions per call; 8.0 reads installed documents.
 *
 * Scenarios, as in 7.0: `global`, a global ALLOW matches; `role`, a permission near the end of the app's list, through
 * the `*` grant; `none`, nothing matches; `role-heavy`, 12 roles granting 30 permissions each plus a `*` grant over 120
 * permissions, nothing matches.
 *
 * `boundary` (RFC 0007 §14.1): `none`, no platform boundary; `1` or `8`, a default boundary of that many ALLOWs whose
 * last one admits every path, so permits stay permits after all of them are tried. `none` must keep the throughput and
 * B/op of the same benchmark without the boundary check (RFC 0007 Q24).
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class FlatAuthorizationBenchmark {
    @Param("global", "role", "none", "role-heavy")
    lateinit var scenario: String

    @Param("none", "1", "8")
    lateinit var boundary: String

    private lateinit var platform: CompiledDocument
    private lateinit var app: CompiledDocument
    private lateinit var tenant: CompiledDocument
    private lateinit var subject: ScopeSubject
    private lateinit var memberships: Memberships
    private lateinit var authz: AuthzRequest
    private val declared = DeclaredScope("app", "")
    private val host = HostScope(null)
    private val compiler = PolicyCompiler()

    private fun statement(name: String, effect: Effect, pattern: String) =
        StatementSource(name, effect, listOf("http:*"), listOf(ResourceSource("http:path:$pattern")))

    /** A 7.x policy: [count] ALLOWs on `/<prefix>/resource<n>` and below, then a DENY on `/<prefix>/admin` and below. */
    private fun policy(id: String, prefix: String, count: Int, attachable: Boolean = false): CompiledPolicy {
        val statements = (1..count).map { statement("s$it", Effect.ALLOW, "/$prefix/resource$it/**") } +
            statement("deny", Effect.DENY, "/$prefix/admin/**")
        return compiler.compile(PolicyId(id), null, statements, attachable)
    }

    @Setup
    fun setup() {
        val global = listOf(policy("global-public", "public", 10), policy("global-docs", "docs", 10), policy("global-health", "health", 5))
        val principal = listOf(
            policy("principal-a", "reports", 10, attachable = true),
            policy("principal-b", "exports", 10, attachable = true),
        )
        val boundaries = if (boundary == "none") {
            emptyList()
        } else {
            val count = boundary.toInt()
            val statements = (1 until count).map { statement("b$it", Effect.ALLOW, "/outside$it/**") } +
                statement("b$count", Effect.ALLOW, "/**")
            listOf(compiler.compile(PolicyId("boundary"), null, statements))
        }
        platform = CompiledDocument.platform(
            global + principal,
            global.map { Binding(BindingId("g-${it.id}"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), it.policyId) },
            emptyList(),
            boundaries,
            boundaries.firstOrNull()?.policyId?.value,
        )

        val heavy = scenario == "role-heavy"
        val permissionCount = if (heavy) 120 else 50
        val permissions = (1..permissionCount).map { statement("p$it", Effect.ALLOW, "/orders/op$it/**") } +
            statement("deny-internal", Effect.DENY, "/orders/internal/**")
        val grants: Map<String, List<String>> = if (heavy) {
            (1..12).associate { role -> "role$role" to (1..30).map { "p${(role * 7 + it) % permissionCount + 1}" }.distinct() } +
                ("admin" to listOf("*"))
        } else {
            mapOf("viewer" to (1..20).map { "p$it" }, "admin" to listOf("*"))
        }
        val byId = permissions.associateBy { it.name }
        val granted = grants.values.flatten().filter { it != "*" }.distinct()
        val policies = granted.map { id -> compiler.compile(PolicyId("perm:app/$id"), null, listOf(byId.getValue(id))) } +
            compiler.compile(PolicyId("perm:app/*"), null, permissions)
        val bindings = grants.flatMap { (role, ids) ->
            ids.map { id ->
                Binding(
                    BindingId("$role/$id"),
                    SubjectRef.Role(null, RoleId(role)),
                    BindingScope(Match.Exactly(AppId("app")), Match.Any),
                    PolicyId("perm:app/$id"),
                )
            }
        }
        app = CompiledDocument.app(AppId("app"), policies, bindings)
        tenant = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList())

        val roles = if (heavy) grants.keys else setOf("viewer", "admin")
        memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId.DEFAULT to roles.mapTo(LinkedHashSet(), ::RoleId)),
            listOf(PolicyId("principal-a"), PolicyId("principal-b")),
        )
        subject = ScopeSubject(TenantId("acme"), authenticated = true, memberships)
        val path = when (scenario) {
            "global" -> "/public/resource3/page"
            "role" -> "/orders/op49/detail"
            "none", "role-heavy" -> "/unknown/path"
            else -> error("Unknown scenario $scenario")
        }
        authz = AuthzRequest(
            subject = Subject("user", "acme", true, emptySet(), emptySet(), emptyMap(), root = false),
            scope = Scope.declared("acme", "app", ""),
            action = Action("http", "GET"),
            resource = Resource("http", path, emptyMap()),
            environment = Environment("127.0.0.1", "request", "device", blocked = false),
        )
        val decision = authorize()
        val expected = when (scenario) {
            "global" -> decision is Decision.Permit && decision.by?.policy?.value == "global-public"
            "role" -> decision is Decision.Permit && decision.by?.policy?.value == "perm:app/*"
            else -> decision == Decision.Deny(null)
        }
        check(expected) { "Scenario $scenario decided $decision" }
        val resolved = (ScopeResolver.resolve(subject, declared, host) as ScopeResolution.Resolved).scope
        val traced = CoSecFlatEngine.evaluate(
            FlatRequest(authz, resolved, memberships, 0),
            SnapshotView(platform, app, tenant),
            TraceLevel.FULL,
        )
        check(traced.decision == decision)
    }

    @Benchmark
    fun authorize(): Decision {
        val scope = (ScopeResolver.resolve(subject, declared, host) as ScopeResolution.Resolved).scope
        val request = FlatRequest(authz, scope, memberships, 0)
        return CoSecFlatEngine.evaluate(request, SnapshotView(platform, app, tenant)).decision
    }
}
