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

package me.ahoo.cosec.shadow

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.MissingPermissionException
import me.ahoo.cosec.api.policy.Policy
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
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.util.concurrent.CopyOnWriteArrayList

class ShadowAppPermissionTest {
    private val diffs = CopyOnWriteArrayList<ShadowDiff>()

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun app(vararg permissions: PermissionData): AppPermission =
        AppPermissionData("shop", groups = listOf(PermissionGroupData("orders", permissions = permissions.toList())))

    private val ship = PermissionData(id = "order.ship", name = "Ship", action = path("/orders/*/ship"))
    private val cancel = PermissionData(id = "order.cancel", name = "Cancel", action = path("/orders/*/cancel"))

    private val policyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(emptyList())
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
        override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
    }

    private val appRolePermissionRepository = object : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: String,
            spaceId: String,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> {
            if (appId != "shop") {
                return Mono.empty()
            }
            return Mono.just(
                AppRolePermissionData(
                    app(ship, cancel),
                    listOf(RolePermissionData("clerk", setOf("order.ship", "order.cancel")))
                )
            )
        }
    }

    private fun shadow(candidates: List<AppPermission>) = ShadowAuthorization(
        delegate = SimpleAuthorization(policyRepository, appRolePermissionRepository),
        policyRepository = policyRepository,
        appRolePermissionRepository = appRolePermissionRepository,
        policyProvider = object : ShadowPolicyProvider {
            override fun getPolicies(): List<Policy> = emptyList()
            override fun getAppPermissions(): List<AppPermission> = candidates
        },
        sink = ShadowEventSink(diffs::add),
    )

    private val clerk = SimpleSecurityContext(SimplePrincipal("user", roles = setOf("clerk")))

    private fun ShadowAuthorization.authorize(path: String, appId: String = "shop"): AuthorizeResult? =
        authorize(EvaluateRequest(path = path, headers = mapOf(AppIdCapable.APP_ID_KEY to appId)), clerk).block()

    @Test
    fun removingAGrantedPermissionIsACandidateError() {
        // The candidate catalog drops "cancel" while the clerk is still granted it: enforced, every request of the
        // role would fail closed, so the candidate run fails on every request, whatever its path.
        val authorization = shadow(listOf(app(ship)))
        authorization.authorize("/orders/1/cancel").assert().isEqualTo(AuthorizeResult.ALLOW)
        authorization.authorize("/orders/1/ship").assert().isEqualTo(AuthorizeResult.ALLOW)
        diffs.assert().hasSize(2)
        diffs.forEach { diff ->
            diff.kind.assert().isEqualTo(ShadowDiffKind.CANDIDATE_ERROR)
            diff.candidate.error.assert().contains(MissingPermissionException::class.java.name, "[order.cancel]")
        }
    }

    @Test
    fun narrowingAPermissionNarrows() {
        // The candidate keeps "cancel" but no longer matches the cancel path.
        val authorization = shadow(listOf(app(ship, cancel.copy(action = path("/orders/*/cancel/confirmed")))))
        authorization.authorize("/orders/1/cancel").assert().isEqualTo(AuthorizeResult.ALLOW)
        diffs.single().apply {
            kind.assert().isEqualTo(ShadowDiffKind.NARROWED)
            enforced.decidedBy?.permissionId.assert().isEqualTo("order.cancel")
            candidate.decidedBy.assert().isNull()
        }
        authorization.authorize("/orders/1/ship")
        diffs.assert().hasSize(1)
    }

    @Test
    fun otherAppsAreUntouched() {
        shadow(listOf(app(ship))).authorize("/orders/1/cancel", appId = "other")
        diffs.assert().isEmpty()
    }
}
