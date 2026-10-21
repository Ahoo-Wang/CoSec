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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.engine.Decisions
import me.ahoo.cosec.authorization.engine.LegacyFacts
import me.ahoo.cosec.authorization.engine.PolicySetLoader
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.principal.SimplePrincipal
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import reactor.core.publisher.Mono

/**
 * What [EngineAuthorization] adds around the engine, without the evaluation: the engine's view of the request, the
 * three-layer read and adaptation, and the mapping of an implicit deny back. Same data as the `none` scenario of
 * [SimpleAuthorizationBenchmark]; its `gc.alloc.rate.norm` is the adapter's own allocation per request.
 */
@State(Scope.Benchmark)
open class EngineAdapterBenchmark {
    private lateinit var loader: PolicySetLoader
    private val request = EvaluateRequest(path = "/unknown/path")
    private val securityContext = SimpleSecurityContext(
        SimplePrincipal(id = "user", policies = setOf("principal-a", "principal-b"), roles = setOf("viewer", "admin")),
    )

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun policy(id: String, type: PolicyType, prefix: String): Policy = PolicyData(
        id,
        "category",
        id,
        "",
        type,
        "tenant",
        statements = (1..10).map {
            StatementData(name = "s$it", effect = Effect.ALLOW, action = path("/$prefix/resource$it/**"))
        },
    )

    @Setup
    fun setup() {
        val global = Mono.just(listOf(policy("g1", PolicyType.GLOBAL, "public"), policy("g2", PolicyType.GLOBAL, "docs")))
        val principal = Mono.just(listOf(policy("principal-a", PolicyType.CUSTOM, "a"), policy("principal-b", PolicyType.CUSTOM, "b")))
        val permissions = (1..50).map { PermissionData(id = "p$it", name = "p$it", action = path("/orders/op$it/**")) }
        val appPermission = AppPermissionData("app", groups = listOf(PermissionGroupData("g", permissions = permissions)))
        // Built once, unlike SimpleAuthorizationBenchmark: only the adapter's allocation is measured.
        val role = Mono.just<AppRolePermission>(
            AppRolePermissionData(
                appPermission,
                listOf(RolePermissionData("viewer", (1..20).map { "p$it" }.toSet()), RolePermissionData("admin", setOf("*"))),
            ),
        )
        loader = PolicySetLoader(
            object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = global
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = principal
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            },
            object : AppRolePermissionRepository {
                override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>): Mono<AppRolePermission> =
                    role
            },
        )
    }

    @Benchmark
    fun adapt(): AuthorizationDecision? {
        val authzRequest = LegacyFacts.authzRequest(request, securityContext)
        return loader.load(authzRequest)
            .map { policies: PolicySet ->
                PolicySetLoader.requiresBlocking(policies)
                Decisions.of(Decision.Deny(null), policies, authzRequest.host as LegacyFacts)
            }.block()
    }
}
