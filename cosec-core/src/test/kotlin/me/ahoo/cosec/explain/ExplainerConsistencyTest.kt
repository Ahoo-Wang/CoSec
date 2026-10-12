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

package me.ahoo.cosec.explain

import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import kotlin.random.Random

/**
 * The guard of the explainer's design: over random policies, roles and requests, the trace always points at the
 * rule the real authorization decided by, and the explained result is the real one.
 */
class ExplainerConsistencyTest {
    private val patterns = listOf("/a/**", "/a/b/**", "/b/**", "/a/*/c", "/**")
    private val paths = listOf("/a/x", "/a/b/c", "/b/y", "/a/z/c", "/c")
    private val roles = listOf("r1", "r2", "r3")

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun Random.effect() = if (nextInt(4) == 0) Effect.DENY else Effect.ALLOW

    private fun Random.policy(id: String, type: PolicyType): Policy = PolicyData(
        id = id,
        category = "",
        name = id,
        description = "",
        type = type,
        tenantId = "tenant",
        statements = List(nextInt(4)) { index ->
            StatementData(name = "s$index", effect = effect(), action = path(patterns.random(this)))
        },
    )

    @Test
    fun traceAgreesWithDecision() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            val globalPolicies = List(random.nextInt(3)) { random.policy("g$it", PolicyType.GLOBAL) }
            val customPolicies = List(random.nextInt(3)) { random.policy("p$it", PolicyType.CUSTOM) }
            val permissions = List(random.nextInt(5)) { index ->
                PermissionData(
                    id = "perm$index",
                    name = "perm$index",
                    effect = random.effect(),
                    action = path(patterns.random(random))
                )
            }
            val appRolePermission = AppRolePermissionData(
                AppPermissionData("app", groups = listOf(PermissionGroupData("g", permissions = permissions))),
                roles.map { role ->
                    val granted = if (random.nextInt(5) == 0) {
                        setOf(
                            "*"
                        )
                    } else {
                        permissions.filter { random.nextBoolean() }.map { it.id }.toSet()
                    }
                    RolePermissionData(role, granted)
                },
            )
            val policyRepository = object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(globalPolicies)
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
                    Mono.just(customPolicies.filter { it.id in policyIds })

                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            }
            val appRolePermissionRepository = object : AppRolePermissionRepository {
                override fun getAppRolePermission(
                    appId: String,
                    spaceId: String,
                    roleIds: Set<RoleId>
                ): Mono<AppRolePermission> = Mono.just(appRolePermission)
            }
            val principal = SimplePrincipal(
                id = "user",
                policies = customPolicies.filter { random.nextBoolean() }.map { it.id }.toSet(),
                roles = roles.filter { random.nextBoolean() }.toSet(),
            )
            val request =
                EvaluateRequest(path = paths.random(random), headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))
            val expected = SimpleAuthorization(policyRepository, appRolePermissionRepository)
                .authorize(request, SimpleSecurityContext(principal)).block()
            val explanation = checkNotNull(
                AuthorizationExplainer(policyRepository, appRolePermissionRepository)
                    .explain(request, principal, ExplainOptions(exhaustive = random.nextBoolean())).block(),
            )
            explanation.warnings.assert().describedAs("iteration $iteration").isEmpty()
            explanation.authorized.assert().describedAs("iteration $iteration").isEqualTo(expected?.authorized)
        }
    }

    companion object {
        private const val SEED = 20261012
        private const val ITERATIONS = 2_000
    }
}
