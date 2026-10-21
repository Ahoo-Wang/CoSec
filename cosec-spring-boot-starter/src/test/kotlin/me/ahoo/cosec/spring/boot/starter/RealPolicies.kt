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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import reactor.core.publisher.Mono

/** A small policy set behind a real authorization implementation, for the web adapter tests. */
internal object RealPolicies {
    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private class FailingCondition(private val error: RuntimeException) : ConditionMatcher {
        override val type: String = "failing"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
        override fun match(request: Request, securityContext: SecurityContext): Boolean = throw error
    }

    private val global = PolicyData(
        id = "global",
        category = "",
        name = "global",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = listOf(
            StatementData(name = "public", effect = Effect.ALLOW, action = path("/public/**")),
            StatementData(name = "admin", effect = Effect.DENY, action = path("/admin/**")),
            StatementData(
                name = "slow",
                action = path("/slow/**"),
                condition = FailingCondition(RegexTimeoutException("timeout")),
            ),
            StatementData(
                name = "broken",
                action = path("/broken/**"),
                condition = FailingCondition(IllegalStateException("broken")),
            ),
        ),
    )

    private val appRolePermission = AppRolePermissionData(
        appPermission = AppPermissionData(
            id = "",
            groups = listOf(
                PermissionGroupData(
                    "orders",
                    permissions = listOf(
                        PermissionData(id = "orders.read", name = "read", action = path("/orders/**")),
                        PermissionData(
                            id = "internal",
                            name = "internal",
                            effect = Effect.DENY,
                            action = path("/public/internal"),
                        ),
                    ),
                ),
            ),
        ),
        rolePermissions = listOf(RolePermissionData("viewer", setOf(AppRolePermission.ALL_PERMISSION_ID))),
    )

    private val policyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(global))
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
        override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
    }

    private val appRolePermissionRepository = object : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: String,
            spaceId: String,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> = Mono.just(appRolePermission)
    }

    fun authorization(impl: AuthorizationImpl): DecisionAuthorization =
        impl.create(policyRepository, appRolePermissionRepository)
}
