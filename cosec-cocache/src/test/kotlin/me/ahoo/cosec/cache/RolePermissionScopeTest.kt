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
import me.ahoo.cosec.api.principal.SpacedRoleId
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
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * RFC 0001, D3: role grants are stored per (role, space), without the app, and both app and space are chosen by
 * the client's `CoSec-App-Id` / `CoSec-Space-Id` headers. Nothing ties a principal to a space.
 *
 * These tests state the semantics the RFC proposes: a grant made for one app does not apply in another, and a
 * principal cannot pick up the grants of a space it is not a member of. They fail today because the model cannot
 * express either constraint, not because of a single faulty line.
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

    private fun authorization(grants: Map<SpacedRoleId, Set<String>>): SimpleAuthorization {
        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("blog") } returns blogAdmin
        every { appPermissionCache.get("billing") } returns billingAdmin
        val rolePermissionCache = mockk<RolePermissionCache>()
        every { rolePermissionCache.get(any<SpacedRoleId>()) } answers { grants[firstArg()] }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        }
        return SimpleAuthorization(
            policyRepository,
            RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache),
        )
    }

    private fun request(appId: String, spaceId: String = "") = EvaluateRequest(
        path = "/anything",
        method = "POST",
        headers = mapOf(AppIdCapable.APP_ID_KEY to appId, SpaceIdCapable.SPACE_ID_KEY to spaceId)
            .filterValues { it.isNotEmpty() },
    )

    @Test
    fun wildcardGrantForOneAppDoesNotApplyToAnother() {
        // The blog team grants its editors every blog permission.
        val authorization = authorization(mapOf(SpacedRoleId("editor") to setOf(AppRolePermission.ALL_PERMISSION_ID)))
        val context = SimpleSecurityContext(SimplePrincipal("alice", roles = setOf("editor")))

        // Control: allowed in the blog app.
        checkNotNull(authorization.decide(request("blog"), context).block()).result.authorized.assert().isTrue()

        // The same editor switches the header to the billing app.
        val billing = checkNotNull(
            authorization.decide(request("billing"), SimpleSecurityContext(context.principal)).block()
        )
        billing.result.authorized.assert().isFalse()
    }

    @Test
    fun principalCannotPickUpGrantsOfAnotherSpace() {
        // "admin" means full access in team-a; alice is admin of team-b only, where admin grants nothing in blog.
        val authorization = authorization(
            mapOf(SpacedRoleId("admin", "team-a") to setOf(AppRolePermission.ALL_PERMISSION_ID)),
        )
        val alice = SimplePrincipal("alice", roles = setOf("admin"))

        val ownSpace =
            checkNotNull(authorization.decide(request("blog", "team-b"), SimpleSecurityContext(alice)).block())
        ownSpace.result.authorized.assert().isFalse()

        // alice sends CoSec-Space-Id: team-a.
        val otherSpace = checkNotNull(
            authorization.decide(request("blog", "team-a"), SimpleSecurityContext(alice)).block()
        )
        otherSpace.result.authorized.assert().isFalse()
    }
}
