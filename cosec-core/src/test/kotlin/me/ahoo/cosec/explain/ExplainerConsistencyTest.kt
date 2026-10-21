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
import me.ahoo.cosec.api.permission.MissingPermissionException
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
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.condition.CONDITION_MATCHER_NEGATE_KEY
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

    /** A grant may name "ghost", which the catalog does not define; one app in four never applies. */
    private fun Random.appRolePermissionWithGhosts(): AppRolePermission {
        val permissions = List(nextInt(5)) { index ->
            PermissionData(
                id = "perm$index",
                name = "perm$index",
                effect = effect(),
                action = path(patterns.random(this))
            )
        }
        val ids = permissions.map { it.id } + "ghost"
        val condition = if (nextInt(4) == 0) never else AllConditionMatcher.INSTANCE
        return AppRolePermissionData(
            AppPermissionData("app", condition, listOf(PermissionGroupData("g", permissions = permissions))),
            roles.map { role ->
                val granted = if (nextInt(5) == 0) listOf("*") else ids.shuffled(this).filter { nextBoolean() }
                RolePermissionData(role, granted.toCollection(LinkedHashSet()))
            },
        )
    }

    private fun repositories(globalPolicies: List<Policy>, appRolePermission: AppRolePermission) =
        object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(globalPolicies)
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        } to object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<RoleId>
            ): Mono<AppRolePermission> = Mono.just(appRolePermission)
        }

    /**
     * Grants that sometimes name an undefined id: the explanation warns [ExplainWarningCode.MISSING_PERMISSION]
     * exactly when real authorization fails on it, and otherwise agrees with the decision.
     */
    @Test
    fun missingPermissionWarningAgreesWithFailure() {
        val random = Random(SEED)
        var failures = 0
        repeat(ITERATIONS) { iteration ->
            val globalPolicies = List(random.nextInt(3)) { random.policy("g$it", PolicyType.GLOBAL) }
            val (policyRepository, appRolePermissionRepository) =
                repositories(globalPolicies, random.appRolePermissionWithGhosts())
            val principal = SimplePrincipal(id = "user", roles = roles.filter { random.nextBoolean() }.toSet())
            val request =
                EvaluateRequest(path = paths.random(random), headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))
            val expected = SimpleAuthorization(policyRepository, appRolePermissionRepository)
                .authorize(request, SimpleSecurityContext(principal))
                .map { Result.success(it) }
                .onErrorResume { Mono.just(Result.failure(it)) }
                .block()!!
            val explanation = checkNotNull(
                AuthorizationExplainer(policyRepository, appRolePermissionRepository)
                    .explain(request, principal, ExplainOptions(exhaustive = random.nextBoolean())).block(),
            )
            val described = "iteration $iteration"
            val failed = expected.exceptionOrNull()
            if (failed != null) {
                failures++
                failed.assert().describedAs(described).isInstanceOf(MissingPermissionException::class.java)
                explanation.status.assert().describedAs(described).isEqualTo(SecurityStatus.ERROR)
                explanation.warnings.map { it.code }.assert().describedAs(described)
                    .containsExactly(ExplainWarningCode.MISSING_PERMISSION)
            } else {
                explanation.warnings.assert().describedAs(described).isEmpty()
                explanation.authorized.assert().describedAs(described).isEqualTo(expected.getOrThrow().authorized)
            }
        }
        // Both outcomes must actually occur for the comparison to mean anything.
        failures.assert().isBetween(1, ITERATIONS - 1)
    }

    private val never = AllConditionMatcher(mapOf(CONDITION_MATCHER_NEGATE_KEY to true).asConfiguration())

    companion object {
        private const val SEED = 20261012
        private const val ITERATIONS = 2_000
    }
}
