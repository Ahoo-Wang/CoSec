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

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
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
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import reactor.core.publisher.Mono

/**
 * Authorization hot path with in-memory repositories.
 *
 * The role repository rebuilds [AppRolePermissionData] per call, as the Redis-backed repository does.
 *
 * Scenarios:
 * - `global`: matched by a global ALLOW (all layers still evaluated for explicit denies)
 * - `role`: matched by a role permission near the end of the app's permission list
 * - `none`: nothing matches, full traversal ending in an implicit deny
 */
@State(Scope.Benchmark)
open class SimpleAuthorizationBenchmark {
    @Param("global", "role", "none")
    lateinit var scenario: String

    private lateinit var authorization: SimpleAuthorization
    private lateinit var request: EvaluateRequest
    private lateinit var securityContext: SimpleSecurityContext

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun policy(id: String, type: PolicyType, prefix: String, statementCount: Int): Policy {
        val statements = (1..statementCount).map {
            StatementData(name = "s$it", effect = Effect.ALLOW, action = path("/$prefix/resource$it/**"))
        } + StatementData(name = "deny", effect = Effect.DENY, action = path("/$prefix/admin/**"))
        return PolicyData(id, "category", id, "", type, "tenant", statements = statements)
    }

    @Setup
    fun setup() {
        val globalPolicies = listOf(
            policy("global-public", PolicyType.GLOBAL, "public", 10),
            policy("global-docs", PolicyType.GLOBAL, "docs", 10),
            policy("global-health", PolicyType.GLOBAL, "health", 5),
        )
        val principalPolicies = listOf(
            policy("principal-a", PolicyType.CUSTOM, "reports", 10),
            policy("principal-b", PolicyType.CUSTOM, "exports", 10),
        )
        val permissions = (1..50).map {
            PermissionData(id = "p$it", name = "p$it", action = path("/orders/op$it/**"))
        } + PermissionData(id = "deny-internal", name = "deny", effect = Effect.DENY, action = path("/orders/internal/**"))
        val appPermission = AppPermissionData(
            id = "app",
            groups = permissions.chunked(10).mapIndexed { index, group -> PermissionGroupData("g$index", permissions = group) },
        )
        val rolePermissions: Map<RoleId, Set<String>> = mapOf(
            "admin" to setOf(AppRolePermission.ALL_PERMISSION_ID),
            "viewer" to (1..20).map { "p$it" }.toSet(),
        )
        val policyRepository = object : PolicyRepository {
            private val global = Mono.just(globalPolicies)
            private val principal = Mono.just(principalPolicies)
            override fun getGlobalPolicy(): Mono<List<Policy>> = global
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = principal
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        }
        val appRolePermissionRepository = object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<RoleId>
            ): Mono<AppRolePermission> = Mono.fromCallable {
                AppRolePermissionData(
                    appPermission,
                    roleIds.mapNotNull { roleId -> rolePermissions[roleId]?.let { RolePermissionData(roleId, it) } },
                )
            }
        }
        authorization = SimpleAuthorization(policyRepository, appRolePermissionRepository)
        val principal = SimplePrincipal(
            id = "user",
            policies = setOf("principal-a", "principal-b"),
            roles = setOf("viewer", "admin"),
        )
        securityContext = SimpleSecurityContext(principal)
        request = EvaluateRequest(
            path = when (scenario) {
                "global" -> "/public/resource3/page"
                "role" -> "/orders/op49/detail"
                else -> "/unknown/path"
            },
        )
    }

    @Benchmark
    fun authorize(): AuthorizeResult? = authorization.authorize(request, securityContext).block()
}
