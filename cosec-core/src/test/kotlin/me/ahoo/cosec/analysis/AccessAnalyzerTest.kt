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

package me.ahoo.cosec.analysis

import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AccessAnalyzerTest {
    private val analyzer = AccessAnalyzer.load(
        policyLocations = listOf("classpath:cosec-analysis/*-policy.json"),
        appPermissionLocations = listOf("classpath:cosec-policy/test-app-permission.json"),
        roles = mapOf(
            "manage" to mapOf(
                "shipper" to setOf("manage.order.ship"),
                "admin" to setOf("*"),
                "invoicer@team-a" to setOf("manage.order.issueInvoice"),
            ),
        ),
    )

    private fun request(path: String, method: String = "GET", appId: String = "", spaceId: String = "") =
        EvaluateRequest(
            path = path,
            method = method,
            headers = mapOf(AppIdCapable.APP_ID_KEY to appId, SpaceIdCapable.SPACE_ID_KEY to spaceId),
        )

    @Test
    fun whoCanReadOrders() {
        val paths = analyzer.whoCan(request("/orders/42"))
        paths.allows.map { it.source to it.name }.assert().containsExactlyInAnyOrder(
            AccessSource.GLOBAL_POLICY to "read-orders",
            AccessSource.PRINCIPAL_POLICY to "manage-orders",
        )
        paths.denies.assert().isEmpty()
        val manage = paths.allows.single { it.name == "manage-orders" }
        manage.policyId.assert().isEqualTo("ops")
        // The ops policy has a condition, so it applies only to some principals.
        manage.conditional.assert().isTrue()
        paths.allows.single { it.name == "read-orders" }.conditional.assert().isFalse()
    }

    @Test
    fun whoCanReachInternalOrdersIncludesTheDeny() {
        val paths = analyzer.whoCan(request("/orders/internal/1", "DELETE"))
        paths.allows.map { it.name }.assert().containsExactly("manage-orders")
        paths.denies.single().apply {
            name.assert().isEqualTo("no-internal")
            effect.assert().isEqualTo(Effect.DENY)
            statementIndex.assert().isEqualTo(1)
        }
    }

    @Test
    fun templatedActionsArePossibleMatches() {
        val allows = analyzer.whoCan(request("/users/bob/profile")).allows
        allows.single().apply {
            name.assert().isEqualTo("own-profile")
            templated.assert().isTrue()
        }
    }

    @Test
    fun whoCanShipThroughRoles() {
        val allows = analyzer.whoCan(request("/order/ship", "POST", appId = "manage")).allows
        allows.filter { it.source == AccessSource.ROLE_PERMISSION }.map { it.roleId }
            .assert().containsExactlyInAnyOrder("shipper", "admin")
        allows.first { it.roleId == "shipper" }.apply {
            permissionId.assert().isEqualTo("manage.order.ship")
            appId.assert().isEqualTo("manage")
            spaceId.assert().isEqualTo("")
            // The app permission has a condition (authenticated, office network, default tenant).
            conditional.assert().isTrue()
        }
        // Roles are taken from the request's app only.
        analyzer.whoCan(request("/order/ship", "POST", appId = "other")).allows
            .none { it.source == AccessSource.ROLE_PERMISSION }.assert().isTrue()
    }

    @Test
    fun whoCanTakesRolesFromTheRequestSpace() {
        fun roleIds(spaceId: String) =
            analyzer.whoCan(request("/order/issueInvoice", "POST", appId = "manage", spaceId = spaceId)).allows
                .filter { it.source == AccessSource.ROLE_PERMISSION }.map { it.roleId to it.spaceId }
        // Default-space grants do not apply in team-a, and team-a grants do not apply elsewhere.
        roleIds("team-a").assert().containsExactly("invoicer" to "team-a")
        roleIds("").assert().containsExactly("admin" to "")
        roleIds("team-b").assert().isEmpty()
    }

    @Test
    fun grantsOf() {
        val shipper = analyzer.grantsOf(SimplePrincipal("bob", policies = setOf("ops"), roles = setOf("shipper")))
        shipper.map { it.name }.assert().containsExactlyInAnyOrder(
            "read-orders",
            "no-internal",
            "manage-orders",
            "own-profile",
            "Ship",
        )
        val anyone = analyzer.grantsOf(SimplePrincipal("carol"), appId = "manage")
        anyone.map { it.source }.toSet().assert().containsExactly(AccessSource.GLOBAL_POLICY)
        analyzer.grantsOf(SimplePrincipal("dave", roles = setOf("admin")), appId = "manage")
            .count { it.source == AccessSource.ROLE_PERMISSION }.assert().isEqualTo(2)
    }

    @Test
    fun grantsOfFollowTheSpaceOfEachRole() {
        fun rolePermissions(roles: Set<String>, spaceId: String? = null) =
            analyzer.grantsOf(SimplePrincipal("erin", roles = roles), spaceId = spaceId)
                .filter { it.source == AccessSource.ROLE_PERMISSION }.map { it.permissionId }
        // A role in team-a reaches only the grants made in team-a, never the same role's default-space grants.
        rolePermissions(setOf("shipper@team-a")).assert().isEmpty()
        rolePermissions(setOf("invoicer")).assert().isEmpty()
        rolePermissions(setOf("invoicer@team-a")).assert().containsExactly("manage.order.issueInvoice")
        rolePermissions(setOf("shipper", "invoicer@team-a"), spaceId = "team-a")
            .assert().containsExactly("manage.order.issueInvoice")
        rolePermissions(setOf("shipper", "invoicer@team-a"), spaceId = "").assert().containsExactly("manage.order.ship")
        // Malformed roles apply nowhere.
        rolePermissions(setOf("invoicer@team-a@x", "@team-a", "shipper@")).assert().isEmpty()
    }

    @Test
    fun malformedGrantRolesFail() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AccessAnalyzer(policies = emptyList(), roles = mapOf("manage" to mapOf("admin@a@b" to setOf("*"))))
        }
        error.message.assert().contains("admin@a@b").contains("manage")
    }

    @Test
    fun loadFailsOnMissingOrBrokenFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            AccessAnalyzer.load(listOf("classpath:not-exists/*.json"))
        }
        assertThrows(IllegalStateException::class.java) {
            AccessAnalyzer.load(listOf("classpath:cosec-policy-invalid/error-policy.json"))
        }
    }
}
