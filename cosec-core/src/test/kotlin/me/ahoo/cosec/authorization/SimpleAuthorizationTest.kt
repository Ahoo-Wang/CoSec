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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.blacklist.BlacklistChecker
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
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.action.getPathVariables
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

internal class SimpleAuthorizationTest {
    private fun policy(
        id: String,
        effect: Effect,
        condition: ConditionMatcher = AllConditionMatcher.INSTANCE,
        action: me.ahoo.cosec.api.policy.ActionMatcher = AllActionMatcher.INSTANCE,
    ): Policy = PolicyData(
        id = id,
        category = "",
        name = id,
        description = "",
        type = PolicyType.CUSTOM,
        tenantId = SimpleTenant.DEFAULT.tenantId,
        condition = condition,
        statements = listOf(
            StatementData(
                effect = effect,
                action = action,
            )
        ),
    )

    private fun appRolePermission(effect: Effect, roleId: String = "role-id"): AppRolePermissionData {
        val permissionId = "permission-id"
        return AppRolePermissionData(
            appPermission = AppPermissionData(
                id = "app-id",
                groups = listOf(
                    PermissionGroupData(
                        "group",
                        permissions = listOf(
                            PermissionData(
                                id = permissionId,
                                name = "permission",
                                effect = effect,
                                action = AllActionMatcher.INSTANCE,
                            )
                        ),
                    )
                ),
            ),
            rolePermissions = listOf(RolePermissionData(roleId, setOf(permissionId))),
        )
    }

    @Test
    fun authorizeWhenPrincipalIsRoot() {
        val policyRepository = mockk<PolicyRepository>()
        val permissionRepository = mockk<AppRolePermissionRepository>()
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request>()
        val securityContext = mockk<SecurityContext> {
            every { principal.id } returns CoSecPrincipal.ROOT_ID
            every { setAttributeValue(any(), any()) } returns this
        }
        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenBlacklistChecker() {
        val policyRepository = mockk<PolicyRepository>()
        val permissionRepository = mockk<AppRolePermissionRepository>()
        val blacklistChecker = mockk<BlacklistChecker> {
            every { check(any(), any()) } returns Mono.just(false)
        }
        val authorization = SimpleAuthorization(
            policyRepository = policyRepository,
            appRolePermissionRepository = permissionRepository,
            blacklistChecker = blacklistChecker
        )
        val request = mockk<Request>()
        val securityContext = mockk<SecurityContext> {
            every { principal.id } returns CoSecPrincipal.ANONYMOUS_ID
            every { setAttributeValue(any(), any()) } returns this
        }
        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenPolicyIsEmpty() {
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
        }

        authorization.authorize(request, SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalPolicyIsAllowAll() {
        val globalPolicy = mockk<Policy> {
            every { id } returns "globalPolicy"
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns listOf(
                StatementData(
                    effect = Effect.ALLOW,
                    action = AllActionMatcher.INSTANCE,
                ),
            )
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(listOf(globalPolicy))
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
        }

        authorization.authorize(request, SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalPolicyIsDenyAll() {
        val globalPolicy = mockk<Policy> {
            every { id } returns "globalPolicy"
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns listOf(
                StatementData(
                    effect = Effect.DENY,
                    action = AllActionMatcher.INSTANCE,
                ),
            )
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(listOf(globalPolicy))
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request>()

        authorization.authorize(request, SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalAllowConflictsWithPrincipalDeny() {
        val securityContext = SimpleSecurityContext(
            SimpleTenantPrincipal(
                SimplePrincipal("user", policies = setOf("principal-deny")),
                SimpleTenant.DEFAULT,
            )
        )
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns listOf(policy("global-allow", Effect.ALLOW)).toMono()
            every { getPolicies(setOf("principal-deny")) } returns
                listOf(policy("principal-deny", Effect.DENY)).toMono()
        }
        val permissionRepository = mockk<AppRolePermissionRepository>()

        SimpleAuthorization(policyRepository, permissionRepository)
            .authorize(EvaluateRequest(), securityContext)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenPrincipalAllowConflictsWithRoleDeny() {
        val roleId = "role-deny"
        val securityContext = SimpleSecurityContext(
            SimpleTenantPrincipal(
                SimplePrincipal(
                    id = "user",
                    policies = setOf("principal-allow"),
                    roles = setOf(roleId),
                ),
                SimpleTenant.DEFAULT,
            )
        )
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(setOf("principal-allow")) } returns
                listOf(policy("principal-allow", Effect.ALLOW)).toMono()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), setOf(roleId)) } returns
                appRolePermission(Effect.DENY, roleId).toMono()
        }

        SimpleAuthorization(policyRepository, permissionRepository)
            .authorize(EvaluateRequest(), securityContext)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeEvaluatesPolicyConditionOnce() {
        val invocationCount = AtomicInteger()
        val countingCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } answers {
                invocationCount.incrementAndGet()
                true
            }
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns
                listOf(policy("counting-policy", Effect.ALLOW, countingCondition)).toMono()
        }
        val permissionRepository = mockk<AppRolePermissionRepository>()

        SimpleAuthorization(policyRepository, permissionRepository)
            .authorize(EvaluateRequest(), SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()

        invocationCount.get().assert().isEqualTo(1)
    }

    @Test
    fun authorizeStopsEvaluatingConditionsAfterExplicitDeny() {
        val invocationCount = AtomicInteger()
        val countingCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } answers {
                invocationCount.incrementAndGet()
                true
            }
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns listOf(
                policy("global-deny", Effect.DENY),
                policy("unreachable-allow", Effect.ALLOW, countingCondition),
            ).toMono()
        }
        val permissionRepository = mockk<AppRolePermissionRepository>()

        SimpleAuthorization(policyRepository, permissionRepository)
            .authorize(EvaluateRequest(), SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()

        invocationCount.get().assert().isZero()
    }

    @Test
    fun authorizeDoesNotLeakPathVariablesBetweenStatements() {
        val neverMatchCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } returns false
        }
        val stalePathVariableCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } answers {
                secondArg<SecurityContext>().getPathVariables()?.get("id") == "123"
            }
        }
        val pathPolicy = PolicyData(
            id = "path-policy",
            category = "",
            name = "path-policy",
            description = "",
            type = PolicyType.CUSTOM,
            tenantId = SimpleTenant.DEFAULT.tenantId,
            statements = listOf(
                StatementData(
                    effect = Effect.DENY,
                    action = PathActionMatcherFactory.INSTANCE.create("/orders/{id}".asConfiguration()),
                    condition = neverMatchCondition,
                ),
                StatementData(
                    effect = Effect.ALLOW,
                    action = AllActionMatcher.INSTANCE,
                    condition = stalePathVariableCondition,
                ),
            ),
        )
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns listOf(pathPolicy).toMono()
        }
        val permissionRepository = mockk<AppRolePermissionRepository>()

        SimpleAuthorization(policyRepository, permissionRepository)
            .authorize(
                EvaluateRequest(path = "/orders/123"),
                SimpleSecurityContext.anonymous(),
            )
            .test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeDoesNotLeakPathVariablesBetweenCalls() {
        val stalePathVariableCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } answers {
                secondArg<SecurityContext>().getPathVariables()?.get("id") == "123"
            }
        }
        val pathPolicy = policy(
            id = "path-policy",
            effect = Effect.ALLOW,
            action = PathActionMatcherFactory.INSTANCE.create("/orders/{id}".asConfiguration()),
        )
        val stalePolicy = policy(
            id = "stale-policy",
            effect = Effect.ALLOW,
            condition = stalePathVariableCondition,
        )
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returnsMany listOf(
                listOf(pathPolicy).toMono(),
                listOf(stalePolicy).toMono(),
            )
        }
        val permissionRepository = mockk<AppRolePermissionRepository>()
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val securityContext = SimpleSecurityContext.anonymous()

        authorization.authorize(EvaluateRequest(path = "/orders/123"), securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        authorization.authorize(EvaluateRequest(path = "/other"), securityContext)
            .test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalPolicyIsEmptyAndPrincipalIsAllowAll() {
        val principalPolicy = mockk<Policy> {
            every { id } returns "policyId"
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns listOf(
                StatementData(
                    effect = Effect.ALLOW,
                    action = AllActionMatcher.INSTANCE,
                ),
            )
        }
        val securityContext = mockk<SecurityContext> {
            every { principal.authenticated } returns false
            every { principal.id } returns ""
            every { principal.policies } returns setOf("principalPolicy")
            every { principal.roles } returns emptySet()
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.just(listOf(principalPolicy))
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
        }

        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalPolicyIsEmptyAndPrincipalIsDenyAll() {
        val principalPolicy = mockk<Policy> {
            every { id } returns "policyId"
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns listOf(
                StatementData(
                    effect = Effect.DENY,
                    action = AllActionMatcher.INSTANCE,
                ),
            )
        }
        val securityContext = mockk<SecurityContext> {
            every { principal.authenticated } returns false
            every { principal.id } returns ""
            every { principal.policies } returns setOf("principalPolicy")
            every { principal.roles } returns emptySet()
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.just(listOf(principalPolicy))
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
        }

        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalAndPrincipalPolicyIsEmptyAndRoleIsAllowAll() {
        val permissionId = UUID.randomUUID().toString()
        val appRolePermission = AppRolePermissionData(
            appPermission = AppPermissionData(
                id = "appId",
                groups = listOf(
                    PermissionGroupData(
                        "groupName",
                        permissions = listOf(
                            PermissionData(
                                id = permissionId,
                                name = "",
                                effect = Effect.ALLOW,
                                action = AllActionMatcher.INSTANCE,
                            ),
                        ),
                    ),
                ),
            ),
            rolePermissions = listOf(
                RolePermissionData(
                    id = "roleId",
                    permissions = setOf(permissionId),
                ),
            ),
        )

        val securityContext = mockk<SecurityContext> {
            every { principal.authenticated } returns false
            every { principal.id } returns ""
            every { principal.policies } returns emptySet()
            every { principal.roles } returns setOf("rolePolicy")
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
            every { appId } returns "appId"
            every { spaceId } returns "spaceId"
        }

        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalAndPrincipalPolicyIsEmptyAndRoleIsDenyAll() {
        val permissionId = UUID.randomUUID().toString()
        val appRolePermission = AppRolePermissionData(
            appPermission = AppPermissionData(
                id = "appId",
                groups = listOf(
                    PermissionGroupData(
                        "groupName",
                        permissions = listOf(
                            PermissionData(
                                id = permissionId,
                                name = "",
                                effect = Effect.DENY,
                                action = AllActionMatcher.INSTANCE,
                            ),
                        ),
                    ),
                ),
            ),
            rolePermissions = listOf(
                RolePermissionData(
                    id = "roleId",
                    permissions = setOf("*"),
                ),
            ),
        )
        val securityContext = mockk<SecurityContext> {
            every { principal.authenticated } returns false
            every { principal.id } returns ""
            every { principal.policies } returns emptySet()
            every { principal.roles } returns setOf("rolePolicy")
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
            every { appId } returns "appId"
            every { spaceId } returns "spaceId"
        }

        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenRoleAppConditionNotMatched() {
        val permissionId = UUID.randomUUID().toString()
        val neverMatchCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } returns false
        }
        val appRolePermission = AppRolePermissionData(
            appPermission = AppPermissionData(
                id = "appId",
                condition = neverMatchCondition,
                groups = listOf(
                    PermissionGroupData(
                        "groupName",
                        permissions = listOf(
                            PermissionData(
                                id = permissionId,
                                name = "",
                                effect = Effect.ALLOW,
                                action = AllActionMatcher.INSTANCE,
                            ),
                        ),
                    ),
                ),
            ),
            rolePermissions = listOf(
                RolePermissionData(
                    id = "roleId",
                    permissions = setOf(permissionId),
                ),
            ),
        )

        val securityContext = mockk<SecurityContext> {
            every { principal.authenticated } returns false
            every { principal.id } returns ""
            every { principal.policies } returns emptySet()
            every { principal.roles } returns setOf("rolePolicy")
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
            every { appId } returns "appId"
            every { spaceId } returns "spaceId"
        }

        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY)
            .verifyComplete()
    }
}
