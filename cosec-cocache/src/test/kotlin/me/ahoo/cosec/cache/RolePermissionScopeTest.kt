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

package me.ahoo.cosec.cache

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.MissingPermissionException
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/**
 * RFC 0001, D3: role grants used to be stored per (role, space) without the app, and the client chose both app and
 * space with `CoSec-App-Id` / `CoSec-Space-Id`. Grants are now keyed by (app, role, space) and a request in a space
 * is evaluated with only the roles the principal holds in that space (`role@space`; unsuffixed = default space).
 *
 * Runs against a real Redis (`localhost:6379`), so the stored key format is exercised end to end.
 */
class RolePermissionScopeTest {
    private val blogAdmin = AppPermissionData(
        id = "blog",
        groups = listOf(
            PermissionGroupData(
                name = "posts",
                permissions = listOf(
                    PermissionData(id = "posts.manage", name = "", action = AllActionMatcher.INSTANCE)
                ),
            ),
        ),
    )
    private val billingAdmin = AppPermissionData(
        id = "billing",
        groups = listOf(
            PermissionGroupData(
                name = "invoices",
                permissions = listOf(
                    PermissionData(id = "invoices.refund", name = "", action = AllActionMatcher.INSTANCE),
                ),
            ),
        ),
    )

    private lateinit var fixture: RedisRolePermissionFixture
    private lateinit var rolePermissionCache: RolePermissionCache
    private lateinit var authorization: SimpleAuthorization

    @BeforeEach
    fun setup() {
        fixture = RedisRolePermissionFixture()
        fixture.appPermissionCache.put("blog", blogAdmin)
        fixture.appPermissionCache.put("billing", billingAdmin)
        rolePermissionCache = fixture.rolePermissionCache()
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        }
        authorization = SimpleAuthorization(policyRepository, fixture.repository(rolePermissionCache))
    }

    @AfterEach
    fun destroy() {
        fixture.close()
    }

    private fun request(appId: String, spaceId: String = "") = EvaluateRequest(
        path = "/anything",
        method = "POST",
        headers = mapOf(AppIdCapable.APP_ID_KEY to appId, SpaceIdCapable.SPACE_ID_KEY to spaceId)
            .filterValues { it.isNotEmpty() },
    )

    private fun authorized(roles: Set<String>, appId: String, spaceId: String = ""): Boolean {
        val context = SimpleSecurityContext(SimplePrincipal("alice", roles = roles))
        return checkNotNull(authorization.decide(request(appId, spaceId), context).block()).result.authorized
    }

    @Test
    fun wildcardGrantForOneAppDoesNotApplyToAnother() {
        // The blog team grants its editors every blog permission.
        rolePermissionCache.put(AppSpacedRoleId("blog", "editor"), setOf(AppRolePermission.ALL_PERMISSION_ID))
        fixture.redisTemplate.hasKey("${fixture.rolePermissionKeyPrefix}blog:editor").assert().isTrue()

        // Control: allowed in the blog app.
        authorized(setOf("editor"), "blog").assert().isTrue()
        // The same editor switches the header to the billing app.
        authorized(setOf("editor"), "billing").assert().isFalse()
    }

    @Test
    fun sameRoleNameIsGrantedPerApp() {
        rolePermissionCache.put(AppSpacedRoleId("blog", "editor"), setOf("posts.manage"))
        // "posts.manage" is not a billing permission id: the billing grant does not reach the blog permission, and
        // since billing does not define it, every billing request of the role fails closed.
        rolePermissionCache.put(AppSpacedRoleId("billing", "editor"), setOf("posts.manage"))

        authorized(setOf("editor"), "blog").assert().isTrue()
        val editor = SimpleSecurityContext(SimplePrincipal("alice", roles = setOf("editor")))
        authorization.decide(request("billing"), editor)
            .test()
            .expectErrorMatches { it is MissingPermissionException && it.appId == "billing" && it.roleId == "editor" }
            .verify()
    }

    @Test
    fun principalCannotPickUpGrantsOfAnotherSpace() {
        // "admin" means full access in team-a; alice is admin of team-b only, where admin grants nothing in blog.
        rolePermissionCache.put(AppSpacedRoleId("blog", "admin", "team-a"), setOf(AppRolePermission.ALL_PERMISSION_ID))
        fixture.redisTemplate.hasKey("${fixture.rolePermissionKeyPrefix}blog:admin@team-a").assert().isTrue()

        authorized(setOf("admin@team-b"), "blog", "team-b").assert().isFalse()
        // alice sends CoSec-Space-Id: team-a.
        authorized(setOf("admin@team-b"), "blog", "team-a").assert().isFalse()
        // Control: an admin of team-a is allowed there.
        authorized(setOf("admin@team-a"), "blog", "team-a").assert().isTrue()
    }

    @Test
    fun unsuffixedRoleOnlyAppliesInDefaultSpace() {
        rolePermissionCache.put(AppSpacedRoleId("blog", "admin"), setOf(AppRolePermission.ALL_PERMISSION_ID))
        rolePermissionCache.put(AppSpacedRoleId("blog", "admin", "team-a"), setOf(AppRolePermission.ALL_PERMISSION_ID))

        authorized(setOf("admin"), "blog").assert().isTrue()
        // An unsuffixed role is a default-space role: naming another space does not carry it there.
        authorized(setOf("admin"), "blog", "team-a").assert().isFalse()
        // And a spaced role does not apply in the default space.
        authorized(setOf("admin@team-a"), "blog").assert().isFalse()
    }

    @Test
    fun malformedSpacedRoleAppliesNowhere() {
        rolePermissionCache.put(AppSpacedRoleId("blog", "admin", "team-a"), setOf(AppRolePermission.ALL_PERMISSION_ID))

        authorized(setOf("admin@team-a@x"), "blog", "team-a").assert().isFalse()
        authorized(setOf("@team-a"), "blog", "team-a").assert().isFalse()
        authorized(setOf("admin@"), "blog").assert().isFalse()
    }
}
