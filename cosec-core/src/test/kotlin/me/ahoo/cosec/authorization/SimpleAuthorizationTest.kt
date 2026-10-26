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
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.configuration.JsonConfiguration
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
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.*

// The decision corpus of RFC 0002 §8.2: its cases stay together until the legacy implementation is removed.
@Suppress("LargeClass")
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
internal class SimpleAuthorizationTest(private val impl: AuthorizationImpl) {
    @Test
    fun authorizeWhenPrincipalIsRoot() {
        val policyRepository = mockk<PolicyRepository>()
        val permissionRepository = mockk<AppRolePermissionRepository>()
        val authorization = impl.create(
            policyRepository,
            permissionRepository,
            rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT,
        )
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
    fun rootIsOffByDefault() {
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        }
        val securityContext = SimpleSecurityContext(
            SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.PLATFORM),
        )
        impl.create(policyRepository, mockk()).authorize(EvaluateRequest(), securityContext)
            .test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY)
            .verifyComplete()
    }

    @Test
    fun authorizeWhenRootIdIsOutsideThePlatformTenant() {
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        }
        val authorization = impl.create(
            policyRepository,
            mockk(),
            rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT,
        )
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
        val authorization = impl.create(
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
        val authorization = impl.create(policyRepository, permissionRepository)
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
        val authorization = impl.create(policyRepository, permissionRepository)
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
        val authorization = impl.create(policyRepository, permissionRepository)
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
            // The snapshot path checks the tenant's staleness limit (RFC 0003 §4.5).
            every { tenant } returns SimpleTenant.DEFAULT
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.just(listOf(principalPolicy))
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val authorization = impl.create(policyRepository, permissionRepository)
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
            // The snapshot path checks the tenant's staleness limit (RFC 0003 §4.5).
            every { tenant } returns SimpleTenant.DEFAULT
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.just(listOf(principalPolicy))
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        }
        val authorization = impl.create(policyRepository, permissionRepository)
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
                    id = "rolePolicy", // the role the principal holds in the request's space
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
            // The snapshot path checks the tenant's staleness limit (RFC 0003 §4.5).
            every { tenant } returns SimpleTenant.DEFAULT
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = impl.create(policyRepository, permissionRepository)
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
                    id = "rolePolicy", // the role the principal holds in the request's space
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
            // The snapshot path checks the tenant's staleness limit (RFC 0003 §4.5).
            every { tenant } returns SimpleTenant.DEFAULT
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = impl.create(policyRepository, permissionRepository)
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
            every { configuration } returns JsonConfiguration.NULL
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
                    id = "rolePolicy", // the role the principal holds in the request's space
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
            // The snapshot path checks the tenant's staleness limit (RFC 0003 §4.5).
            every { tenant } returns SimpleTenant.DEFAULT
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.empty()
            every { getPolicies(any()) } returns Mono.empty()
        }
        val permissionRepository = mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns appRolePermission.toMono()
        }
        val authorization = impl.create(policyRepository, permissionRepository)
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
        val authorization = impl.create(policyRepository, permissionRepository)
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
            // The snapshot path checks the tenant's staleness limit (RFC 0003 §4.5).
            every { tenant } returns SimpleTenant.DEFAULT
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
        return impl.create(policyRepository, permissionRepository).authorize(request, securityContext)
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
            // The snapshot path checks the tenant's staleness limit (RFC 0003 §4.5).
            every { tenant } returns SimpleTenant.DEFAULT
        }
        impl.create(policyRepository, permissionRepository).authorize(mockk(), securityContext)
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
        impl.create(policyRepository, permissionRepository)
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
        val authorization = impl.create(policyRepository, permissionRepository)
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

    private fun missingPolicyAuthorization(principalPolicies: Mono<List<Policy>>) = impl.create(
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
                it.source.assert().isEqualTo(MissingPolicyException.Source.PRINCIPAL)
                it.message.assert().startsWith("The principal lists policies [suspended]")
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

    private fun allPolicyData(id: String, effect: Effect) = PolicyData(
        id = id,
        category = "",
        name = id,
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = listOf(StatementData(effect = effect, action = AllActionMatcher.INSTANCE)),
    )

    /** A layer read: ready (a scalar Mono, as a local cache hit is) or only on subscription (as a Redis read is). */
    private fun <T : Any> read(value: Mono<T>, deferred: Boolean): Mono<T> = if (deferred) value.hide() else value

    /**
     * A failed read only fails the request when its layer is reached, and only that layer's: a deny in an earlier
     * layer still decides (403), an allow does not hide a later layer's failure (500).
     */
    private fun failedRead(
        deferred: Boolean,
        global: Effect?,
        principal: Effect?,
        roleFails: Boolean,
        principalFails: Boolean
    ): Mono<AuthorizeResult> {
        val down = IllegalStateException("store down")
        val policyRepository = object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> =
                read(Mono.justOrEmpty(global).map { listOf(allPolicyData("global", it)) }, deferred)

            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = read(
                if (principalFails) {
                    Mono.error(
                        down
                    )
                } else {
                    Mono.just(listOfNotNull(principal?.let { allPolicyData("principal", it) }))
                },
                deferred,
            )

            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        }
        val permissionRepository = object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<String>
            ): Mono<AppRolePermission> =
                read(if (roleFails) Mono.error(down) else Mono.empty(), deferred)
        }
        val principalPolicies = if (principal != null || principalFails) setOf("principal") else emptySet()
        val context = SimpleSecurityContext(
            SimplePrincipal("user", policies = principalPolicies, roles = setOf("role"))
        )
        // An app the role layer can read: no repository is asked about an id that names no app.
        val request = EvaluateRequest(headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))
        return impl.create(policyRepository, permissionRepository).authorize(request, context)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun globalDenyDecidesBeforeAFailedPrincipalRead(deferred: Boolean) {
        failedRead(deferred, global = Effect.DENY, principal = null, roleFails = false, principalFails = true)
            .test().expectNext(AuthorizeResult.EXPLICIT_DENY).verifyComplete()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun principalDenyDecidesBeforeAFailedRoleRead(deferred: Boolean) {
        failedRead(deferred, global = null, principal = Effect.DENY, roleFails = true, principalFails = false)
            .test().expectNext(AuthorizeResult.EXPLICIT_DENY).verifyComplete()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun globalAllowDoesNotHideAFailedRoleRead(deferred: Boolean) {
        failedRead(deferred, global = Effect.ALLOW, principal = null, roleFails = true, principalFails = false)
            .test().expectErrorSatisfies { impl.failureOf(it).message.assert().isEqualTo("store down") }.verify()
    }
}
