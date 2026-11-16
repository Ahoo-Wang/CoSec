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
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * The flat engine over bindings (RFC 0004 §12.1), on compiled statements that match an `http:path:` literal.
 *
 * `bindings` is the number of bindings in the app document. Ten of them apply to the principal: role bindings spread
 * over its `roles` roles, each to a permission policy with one DENY and one ALLOW. The rest bind roles the principal
 * does not have, so only the index sees them. The platform holds one `Anyone` binding that never matches, and the
 * tenant document is empty.
 *
 * Scenarios: `allow`, the ALLOW of the last applicable binding matches, so both passes visit every candidate; `deny`,
 * its DENY matches; `none`, an implicit deny. `guard`: `none` gives the permission policies no condition; `shared`
 * gives them one shared condition that holds, as converted app conditions have, which the per-evaluation cache
 * allocates for.
 *
 * `folded` and `lowercase` add one more policy for the principal: [KEYED] ALLOW statements on one path, each with
 * `{"eq": ["scope.app", <id>], "ignoreCase": true}`, more than the index's condition threshold, so their partition is
 * split by the app id's folded value (RFC 0005 §4.1). The request's app id matches one of them: `folded` asks with
 * `OrderApp`, so the lookup folds it to lower case (`StatementIndex.foldAscii`'s slow path, which allocates);
 * `lowercase` asks with `orderapp`, which folds to itself. Nothing else differs, so the B/op between the two is what
 * folding the app id allocates.
 *
 * Expected: throughput and B/op independent of `bindings` (the lookup is a hash, §6.4); B/op with `guard=none` only the
 * evaluator, its resource target, the parsed path and, for `allow` and `deny`, the decision; `folded` above
 * `lowercase` by what the fold allocates, the same for every `bindings`.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class EngineBenchmark {
    @Param("allow", "deny", "none", "folded", "lowercase")
    lateinit var scenario: String

    @Param("10", "1000", "100000")
    var bindings: Int = 0

    @Param("1", "10")
    var roles: Int = 0

    @Param("none", "shared")
    lateinit var guard: String

    private lateinit var request: EngineRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val compiler = PolicyCompiler()
        val condition = if (guard == "shared") {
            compiler.condition(PolicyId("app"), Condition.Eq("subject.authenticated", Literal.Bool(true)))
        } else {
            null
        }
        val appId = when (scenario) {
            "folded" -> AppId("OrderApp")
            "lowercase" -> AppId("orderapp")
            else -> AppId("app")
        }
        val roleIds = List(roles) { "role$it" }
        val policies = (0 until APPLICABLE).map { i ->
            policy(compiler, "perm:$i", condition, path("/deny/$i", Effect.DENY), path("/allow/$i", Effect.ALLOW))
        }
        val filler = policy(compiler, "perm:filler", condition, path("/filler", Effect.ALLOW))
        val keyed = if (appId.value == "app") null else keyed(compiler)
        val roleBindings =
            (0 until APPLICABLE).map { i -> role(appId, "applies$i", roleIds[i % roles], policies[i].policyId) } +
                (0 until bindings - APPLICABLE).map { i -> role(appId, "other$i", "other$i", filler.policyId) } +
                listOfNotNull(keyed?.let { role(appId, "keyed", roleIds[0], it.policyId) })
        val never = policy(compiler, "global", null, path("/never", Effect.DENY))
        val platform = CompiledDocument.platform(
            listOf(never),
            listOf(Binding(BindingId("g"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), never.policyId)),
        )
        val app = CompiledDocument.app(appId, policies + filler + listOfNotNull(keyed), roleBindings)
        val tenant = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList())
        val last = APPLICABLE - 1
        val path = when (scenario) {
            "allow" -> "/allow/$last"
            "deny" -> "/deny/$last"
            "none" -> "/nothing"
            "folded", "lowercase" -> "/keyed"
            else -> error("Unknown scenario $scenario")
        }
        request = request(path, roleIds, appId)
        view = SnapshotView(platform, app, tenant)
        // Also loads the tracing evaluator, as an application that explains decisions would.
        check(evaluate().decision == CoSecEngine.evaluate(request, view, TraceLevel.FULL).decision)
        if (keyed != null) {
            val permit = evaluate().decision as? Decision.Permit
            check(permit?.by?.policy == keyed.policyId) { "The keyed ALLOW must decide, not ${evaluate().decision}." }
        }
    }

    /**
     * [KEYED] ALLOW statements on `/keyed`, each matching one app id ignoring case; the last matches `orderapp`. All
     * share one partition of the path tree, which the index splits by `scope.app` (folded keys).
     */
    private fun keyed(compiler: PolicyCompiler): CompiledPolicy {
        val ids = List(KEYED - 1) { "app$it" } + "orderapp"
        val statements = ids.map { id ->
            StatementSource(
                "keyed-$id",
                Effect.ALLOW,
                listOf("http:*"),
                listOf(ResourceSource("http:path:/keyed")),
                Condition.Eq(Attribute.Kind.SCOPE_APP.path, Literal.Str(id), ignoreCase = true),
            )
        }
        return policy(compiler, "keyed", null, *statements.toTypedArray())
    }

    @Benchmark
    fun evaluate(): Evaluation = CoSecEngine.evaluate(request, view)

    private fun path(path: String, effect: Effect) =
        StatementSource(path, effect, listOf("http:*"), listOf(ResourceSource("http:path:$path")))

    private fun policy(
        compiler: PolicyCompiler,
        id: String,
        condition: CheckedCondition?,
        vararg statements: StatementSource,
    ): CompiledPolicy {
        val compiled = statements.mapIndexed { index, source -> compiler.statement(PolicyId(id), index, source) }
        return CompiledPolicy(PolicyId(id), condition, compiled)
    }

    private fun role(app: AppId, id: String, role: String, policy: PolicyId) =
        Binding(
            BindingId(id),
            SubjectRef.Role(null, RoleId(role)),
            BindingScope(Match.Exactly(app), Match.Any),
            policy,
        )

    private fun request(path: String, roles: List<String>, app: AppId): EngineRequest {
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId.DEFAULT to roles.mapTo(LinkedHashSet(), ::RoleId)),
            emptyList(),
        )
        val subject = ScopeSubject(TenantId("acme"), authenticated = true, memberships, app.value)
        val resolution = ScopeResolver.resolve(subject, "", HostScope(null))
        val authz = AuthzRequest(
            subject = Subject(
                "user",
                true,
                emptyMap(),
                principalBindings = true,
                breakGlass = true,
            ),
            action = Action("http", "GET"),
            resource = Resource("http", path, emptyMap()),
            environment = Environment("127.0.0.1", "request", "device", blocked = false),
        )
        return EngineRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, time = 0)
    }

    private companion object {
        const val APPLICABLE = 10

        /** More than the index's default condition threshold (256), so the keyed partition is split. */
        const val KEYED = 300
    }
}
