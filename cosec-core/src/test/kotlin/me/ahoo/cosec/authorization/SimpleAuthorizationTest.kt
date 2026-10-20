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
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
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

internal class SimpleAuthorizationTest {
    @Test
    fun authorizeWhenPrincipalIsRoot() {
        val policyRepository = mockk<PolicyRepository>()
        val permissionRepository = mockk<AppRolePermissionRepository>()
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val request = mockk<Request>()
        // Root is the root id in the platform tenant; the id alone is not enough (RFC 0001, D5).
        val securityContext = SimpleSecurityContext(
            SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.PLATFORM),
        )
        authorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenRootIdIsOutsideThePlatformTenant() {
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        }
        val authorization = SimpleAuthorization(policyRepository, mockk())
        val principals = listOf(
            SimplePrincipal(CoSecPrincipal.ROOT_ID),
            SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.DEFAULT),
            SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant("acme")),
        )
        principals.forEach { principal ->
            authorization.authorize(EvaluateRequest(), SimpleSecurityContext(principal))
                .test()
                .expectNext(AuthorizeResult.IMPLICIT_DENY)
                .verifyComplete()
        }
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
            every { principal.policies } returns setOf("policyId")
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
            every { principal.policies } returns setOf("policyId")
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
            every { principal.roles } returns setOf("rolePolicy@spaceId")
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
            every { principal.roles } returns setOf("rolePolicy@spaceId")
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
            every { principal.roles } returns setOf("rolePolicy@spaceId")
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

    @Test
    fun authorizeWhenPolicyConditionEvaluatedOnlyOnce() {
        var matchCount = 0
        val countingCondition = mockk<ConditionMatcher> {
            every { match(any<Request>(), any<SecurityContext>()) } answers {
                matchCount++
                true
            }
        }
        val globalPolicy = mockk<Policy> {
            every { id } returns "globalPolicy"
            every { condition } returns countingCondition
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
        val request = mockk<Request>()

        authorization.authorize(request, SimpleSecurityContext.anonymous())
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()

        matchCount.assert().isEqualTo(1)
    }

    private fun allPolicy(policyId: String, effect: Effect): Policy = mockk {
        every { id } returns policyId
        every { condition } returns AllConditionMatcher.INSTANCE
        every { statements } returns listOf(StatementData(effect = effect, action = AllActionMatcher.INSTANCE))
    }

    private fun allRolePermission(effect: Effect): AppRolePermissionData = AppRolePermissionData(
        appPermission = AppPermissionData(
            id = "appId",
            groups = listOf(
                PermissionGroupData(
                    "groupName",
                    permissions = listOf(
                        PermissionData(
                            id = "permissionId",
                            name = "",
                            effect = effect,
                            action = AllActionMatcher.INSTANCE
                        ),
                    ),
                ),
            ),
        ),
        rolePermissions = listOf(RolePermissionData(id = "roleId", permissions = setOf("permissionId"))),
    )

    private fun authorizeLayers(
        globalPolicy: Policy?,
        principalPolicy: Policy?,
        rolePermission: AppRolePermissionData?
    ): Mono<AuthorizeResult> {
        val securityContext = mockk<SecurityContext> {
            every { principal.id } returns "userId"
            every { principal.policies } returns setOfNotNull(principalPolicy?.id)
            every { principal.roles } returns setOf("roleId@spaceId")
            every { setAttributeValue(any(), any()) } returns this
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.justOrEmpty(globalPolicy).map { listOf(it) }
            every { getPolicies(any()) } returns Mono.justOrEmpty(principalPolicy).map { listOf(it) }
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.justOrEmpty(rolePermission)
        }
        val request = mockk<Request> {
            every { appId } returns "appId"
            every { spaceId } returns "spaceId"
        }
        return SimpleAuthorization(policyRepository, permissionRepository).authorize(request, securityContext)
    }

    @Test
    fun authorizeWhenGlobalAllowAndPrincipalDeny() {
        authorizeLayers(allPolicy("global", Effect.ALLOW), allPolicy("principal", Effect.DENY), null)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalAllowAndRoleDeny() {
        authorizeLayers(allPolicy("global", Effect.ALLOW), null, allRolePermission(Effect.DENY))
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenPrincipalAllowAndRoleDeny() {
        authorizeLayers(null, allPolicy("principal", Effect.ALLOW), allRolePermission(Effect.DENY))
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalAllowAndOtherLayersAllow() {
        authorizeLayers(
            allPolicy("global", Effect.ALLOW),
            allPolicy("principal", Effect.ALLOW),
            allRolePermission(Effect.ALLOW)
        ).test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenGlobalDenyShortCircuitsOtherLayers() {
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(listOf(allPolicy("global", Effect.DENY)))
        }
        val permissionRepository = mockk<AppRolePermissionRepository>()
        val securityContext = mockk<SecurityContext> {
            every { principal.id } returns "userId"
            every { principal.policies } returns setOf("principalPolicy")
            every { principal.roles } returns setOf("roleId@spaceId")
            every { setAttributeValue(any(), any()) } returns this
        }
        SimpleAuthorization(policyRepository, permissionRepository).authorize(mockk(), securityContext)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenWildcardRoleMatchesNoPermission() {
        val appRolePermission = AppRolePermissionData(
            appPermission = AppPermissionData(
                id = "appId",
                groups = listOf(
                    PermissionGroupData(
                        "groupName",
                        permissions = listOf(
                            PermissionData(
                                id = "unmatched",
                                name = "",
                                effect = Effect.ALLOW,
                                action = PathActionMatcherFactory.INSTANCE.create("/other/**".asConfiguration()),
                            ),
                        ),
                    ),
                ),
            ),
            rolePermissions = listOf(RolePermissionData(id = "admin", permissions = setOf("*"))),
        )
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns appRolePermission.toMono()
        }
        val securityContext = SimpleSecurityContext(SimplePrincipal("user", roles = setOf("admin")))
        SimpleAuthorization(policyRepository, permissionRepository)
            .authorize(EvaluateRequest(path = "/orders/1"), securityContext)
            .test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun roleLayerOnlySeesRolesScopedToTheRequestSpace() {
        val requested = mutableListOf<Set<String>>()
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission("appId", any(), capture(requested)) } returns Mono.empty()
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
        }
        val authorization = SimpleAuthorization(policyRepository, permissionRepository)
        val principal = SimplePrincipal("user", roles = setOf("admin", "admin@team-a", "viewer@team-b"))
        fun request(spaceId: String) = EvaluateRequest(
            headers = mapOf(AppIdCapable.APP_ID_KEY to "appId", SpaceIdCapable.SPACE_ID_KEY to spaceId)
                .filterValues { it.isNotEmpty() },
        )

        authorization.authorize(request("team-a"), SimpleSecurityContext(principal)).test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY).verifyComplete()
        authorization.authorize(request(""), SimpleSecurityContext(principal)).test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY).verifyComplete()
        // No role of the principal is in team-c, so the repository is not asked at all.
        authorization.authorize(request("team-c"), SimpleSecurityContext(principal)).test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY).verifyComplete()

        requested.assert().containsExactly(setOf("admin"), setOf("admin"))
    }

    private val allowAll = PolicyData(
        id = "allow-all",
        category = "",
        name = "allow-all",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
    )

    private fun missingPolicyAuthorization(principalPolicies: Mono<List<Policy>>) = SimpleAuthorization(
        mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(listOf(allowAll))
            every { getPolicies(any()) } returns principalPolicies
        },
        mockk(),
    )

    @Test
    fun missingPrincipalPolicyFailsClosed() {
        // The missing policy may be the deny that keeps the principal out: the global allow must not win.
        val context = SimpleSecurityContext(SimplePrincipal("user", policies = setOf("allow-all", "suspended")))
        missingPolicyAuthorization(Mono.just(listOf(allowAll)))
            .authorize(EvaluateRequest(path = "/orders"), context)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(MissingPolicyException::class.java)
                (it as MissingPolicyException).policyIds.assert().containsExactly("suspended")
            }
            .verify()
    }

    @Test
    fun emptyPrincipalPoliciesFailClosed() {
        val context = SimpleSecurityContext(SimplePrincipal("user", policies = setOf("suspended")))
        missingPolicyAuthorization(Mono.empty())
            .authorize(EvaluateRequest(path = "/orders"), context)
            .test()
            .expectError(MissingPolicyException::class.java)
            .verify()
    }
}
