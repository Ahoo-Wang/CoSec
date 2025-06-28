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
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.*

internal class SimpleAuthorizationTest {
    @Test
    fun authorizeWhenPrincipalIsRoot() {
        val policyRepository = mockk<PolicyRepository>()
        val permissionRepository = mockk<AppRolePermissionRepository>()
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request>()
        val securityContext = mockk<SecurityContext> {
            every { principal.id } returns CoSecPrincipal.ROOT_ID
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
            every { getAppRolePermission(any(), any()) } returns Mono.empty()
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
            every { getAppRolePermission(any(), any()) } returns Mono.empty()
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
            every { getAppRolePermission(any(), any()) } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request>()

        authorization.authorize(request, SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
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
            every { principal.authenticated() } returns false
            every { principal.id } returns ""
            every { principal.policies } returns setOf("principalPolicy")
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.just(listOf(principalPolicy))
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any()) } returns Mono.empty()
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
            every { principal.authenticated() } returns false
            every { principal.id } returns ""
            every { principal.policies } returns setOf("principalPolicy")
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.just(listOf(principalPolicy))
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any()) } returns Mono.empty()
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
            every { principal.authenticated() } returns false
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
            every { getAppRolePermission(any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
            every { appId } returns "appId"
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
            every { principal.authenticated() } returns false
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
            every { getAppRolePermission(any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request> {
            every { appId } returns "appId"
        }

        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }
}
