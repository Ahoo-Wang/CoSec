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
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.MissingPermissionException
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.explain.ExplainLayer
import me.ahoo.cosec.explain.ExplainOptions
import me.ahoo.cosec.explain.ExplainRequest
import me.ahoo.cosec.explain.ExplainWarningCode
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.condition.CONDITION_MATCHER_NEGATE_KEY
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/**
 * A role grants `deny-delete`, but the app permission catalog no longer defines it (deleted, or the id is mistyped).
 * Skipping the id drops the deny, and a broader global ALLOW lets the request through.
 */
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class MissingPermissionFailClosedTest(private val impl: AuthorizationImpl) {
    private val allowAll = PolicyData(
        id = "allow-all",
        category = "",
        name = "allow-all",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
    )
    private val denyDelete = PermissionData(
        id = "deny-delete",
        name = "deny-delete",
        effect = Effect.DENY,
        action = AllActionMatcher.INSTANCE,
    )
    private val read = PermissionData(id = "read", name = "read", action = AllActionMatcher.INSTANCE)

    private fun catalog(vararg permissions: PermissionData) =
        AppPermissionData(
            id = "app",
            groups = listOf(PermissionGroupData(name = "g", permissions = permissions.toList()))
        )

    private val policyRepository = mockk<PolicyRepository> {
        every { getGlobalPolicy() } returns Mono.just(listOf(allowAll))
    }

    private fun permissions(
        appPermission: AppPermissionData,
        granted: Set<String> = setOf("deny-delete", "read")
    ) = mockk<AppRolePermissionRepository> {
        every { getAppRolePermission("app", "", setOf("restricted")) } returns Mono.just(
            AppRolePermissionData(appPermission, listOf(RolePermissionData("restricted", granted))),
        )
    }

    private fun authorization(vararg permissions: PermissionData) =
        impl.create(policyRepository, permissions(catalog(*permissions)))

    private val request = ExplainRequest(path = "/orders/1", method = "DELETE", appId = "app").toRequest()
    private fun context() = SimpleSecurityContext(SimplePrincipal("user", roles = setOf("restricted")))

    @Test
    fun presentDenyPermissionDenies() {
        val decision = checkNotNull(authorization(denyDelete, read).decide(request, context()).block())
        decision.result.authorized.assert().isFalse()
    }

    @Test
    fun missingDenyPermissionDoesNotTurnIntoAllow() {
        val decision = authorization(read).decide(request, context()).onErrorResume { Mono.empty() }.block()
        (decision?.result?.authorized ?: false).assert().isFalse()
    }

    @Test
    fun missingPermissionFailsWithMissingPermissionException() {
        authorization(read).decide(request, context())
            .test()
            .consumeErrorWith {
                it.assert().isInstanceOf(MissingPermissionException::class.java)
                (it as MissingPermissionException).apply {
                    appId.assert().isEqualTo("app")
                    roleId.assert().isEqualTo("restricted")
                    permissionIds.assert().containsExactly("deny-delete")
                }
            }.verify()
    }

    private fun explain(appRolePermissions: AppRolePermissionRepository) = checkNotNull(
        AuthorizationExplainer(policyRepository, appRolePermissions).explain(request, context().principal).block(),
    )

    @Test
    fun missingPermissionDoesNotMatterWhenTheAppConditionDoesNotMatch() {
        // The layer does not apply, so even a present deny would not: the global allow decides either way.
        val never = AllConditionMatcher(mapOf(CONDITION_MATCHER_NEGATE_KEY to true).asConfiguration())
        val appRolePermissions = permissions(catalog(read).copy(condition = never))
        val decision =
            checkNotNull(impl.create(policyRepository, appRolePermissions).decide(request, context()).block())
        decision.result.assert().isEqualTo(AuthorizeResult.ALLOW)
        explain(appRolePermissions).warnings.assert().isEmpty()
    }

    @Test
    fun denyMatchedBeforeTheMissingPermissionStillDenies() {
        // The deny pass stops at the matched deny; the request is denied either way.
        val appRolePermissions = permissions(catalog(denyDelete, read), granted = linkedSetOf("deny-delete", "typo"))
        val decision =
            checkNotNull(impl.create(policyRepository, appRolePermissions).decide(request, context()).block())
        decision.result.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        explain(appRolePermissions).apply {
            status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
            warnings.assert().isEmpty()
        }
    }

    @Test
    fun missingPermissionBeforeTheDenyFails() {
        val appRolePermissions = permissions(catalog(denyDelete, read), granted = linkedSetOf("typo", "deny-delete"))
        impl.create(policyRepository, appRolePermissions).decide(request, context())
            .test()
            .expectErrorMatches { it is MissingPermissionException && it.permissionIds == setOf("typo") }
            .verify()
        explain(
            appRolePermissions
        ).warnings.map { it.code }.assert().containsExactly(ExplainWarningCode.MISSING_PERMISSION)
    }

    @Test
    fun earlierLayerDenyIsNotReportedAsMissingPermission() {
        // An exhaustive explanation narrates the role layer after a global deny, which real authorization never reaches.
        val denyAll = PolicyData(
            id = "deny-all",
            category = "",
            name = "deny-all",
            description = "",
            type = PolicyType.GLOBAL,
            tenantId = "",
            statements = listOf(StatementData(effect = Effect.DENY, action = AllActionMatcher.INSTANCE)),
        )
        val policies = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(listOf(denyAll))
        }
        val explanation = checkNotNull(
            AuthorizationExplainer(policies, permissions(catalog(read)))
                .explain(request, context().principal, ExplainOptions(exhaustive = true)).block(),
        )
        explanation.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun wildcardStillGrantsEveryPermission() {
        val authorization = impl.create(
            policyRepository,
            permissions(catalog(denyDelete, read), granted = setOf(AppRolePermission.ALL_PERMISSION_ID, "typo")),
        )
        val decision = checkNotNull(authorization.decide(request, context()).block())
        decision.result.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        (decision.verifyContext as RoleVerifyContext).permission.id.assert().isEqualTo("deny-delete")
    }

    @Test
    fun pipelineAnswersError() {
        val requestParser = object : RequestParser<Request> {
            override fun parse(request: Request): Request = request
        }
        SecurityPipeline(requestParser, SecurityContextParser { context() }, authorization(read))
            .evaluate(request)
            .test()
            .consumeNextWith { it.status.assert().isEqualTo(SecurityStatus.ERROR) }
            .verifyComplete()
    }

    @Test
    fun checkerDenies() {
        AuthorizationChecker(authorization(read))
            .check(context(), CheckRequest(path = "/orders/1", method = "DELETE", appId = "app", spaceId = ""))
            .test()
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun explainerWarnsAndAnswersError() {
        val explanation = checkNotNull(
            AuthorizationExplainer(policyRepository, permissions(catalog(read))).explain(request, context().principal)
                .block(),
        )
        explanation.status.assert().isEqualTo(SecurityStatus.ERROR)
        explanation.authorized.assert().isFalse()
        explanation.warnings.map { it.code }.assert().containsExactly(ExplainWarningCode.MISSING_PERMISSION)
        explanation.warnings.single().message.assert().contains("[restricted]", "[deny-delete]", "[app]")
        // The trace narrates the permissions that exist.
        explanation.layers.single { it.layer == ExplainLayer.ROLE_PERMISSION }.roles.single().permissions
            .map { it.id }.assert().containsExactly("read")
    }
}
