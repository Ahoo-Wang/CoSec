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

package me.ahoo.cosec.explain

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.limiter.RateLimit
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.isPolicyEvaluation
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AuthorizationExplainerTest {
    private val globalPolicies = mutableListOf<Policy>()
    private val storedPolicies = mutableMapOf<String, Policy>()
    private var appRolePermission: AppRolePermission? = null
    private val repositoryReads = AtomicInteger()

    private val policyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.fromCallable {
            repositoryReads.incrementAndGet()
            globalPolicies.toList()
        }

        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.fromCallable {
            repositoryReads.incrementAndGet()
            policyIds.mapNotNull { storedPolicies[it] }
        }

        override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
    }

    private val appRolePermissionRepository = object : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: String,
            spaceId: String,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> = Mono.justOrEmpty(appRolePermission)
    }

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun policy(
        id: String,
        vararg statements: StatementData,
        type: PolicyType = PolicyType.GLOBAL,
        condition: ConditionMatcher = AllConditionMatcher.INSTANCE
    ) = PolicyData(id, "", id, "", type, "tenant", condition, statements.toList())

    private fun allow(pattern: String, name: String = pattern) =
        StatementData(name = name, effect = Effect.ALLOW, action = path(pattern))

    private fun deny(pattern: String, name: String = pattern) =
        StatementData(name = name, effect = Effect.DENY, action = path(pattern))

    private fun explainer(
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rateLimitRuleProvider: RateLimitRuleProvider? = null
    ) = AuthorizationExplainer(
        policyRepository,
        appRolePermissionRepository,
        blacklistChecker,
        RootPrincipalMatcher.DEFAULT,
        rateLimitRuleProvider,
    )

    private fun AuthorizationExplainer.explainOf(
        path: String,
        principal: CoSecPrincipal = SimplePrincipal("user"),
        options: ExplainOptions = ExplainOptions()
    ): Explanation = checkNotNull(
        explain(
            EvaluateRequest(path = path, headers = mapOf(AppIdCapable.APP_ID_KEY to "app")),
            principal,
            options,
        ).block(),
    )

    private fun Explanation.layer(layer: ExplainLayer) = layers.single { it.layer == layer }

    @Test
    fun globalAllow() {
        globalPolicies += policy("public", allow("/docs/**"), allow("/public/**"))
        val explanation = explainer().explainOf("/public/page")
        explanation.status.assert().isEqualTo(SecurityStatus.ALLOWED)
        explanation.authorized.assert().isTrue()
        explanation.decidedBy.assert().isEqualTo(
            DecisionRef(ExplainLayer.GLOBAL_POLICY, "public", 1, "/public/**"),
        )
        explanation.warnings.assert().isEmpty()
        val global = explanation.layer(ExplainLayer.GLOBAL_POLICY)
        global.state.assert().isEqualTo(LayerState.EVALUATED)
        global.result.assert().isEqualTo(VerifyResult.ALLOW)
        global.policies.single().statements.map { it.outcome }
            .assert().containsExactly(StatementOutcome.ACTION_MISS, StatementOutcome.MATCHED)
        explanation.layer(ExplainLayer.PRINCIPAL_POLICY).state.assert().isEqualTo(LayerState.EMPTY)
        explanation.layer(ExplainLayer.ROLE_PERMISSION).state.assert().isEqualTo(LayerState.EMPTY)
    }

    @Test
    fun principalDenyOverridesGlobalAllow() {
        globalPolicies += policy("public", allow("/orders/**"))
        storedPolicies["no-orders"] = policy("no-orders", deny("/orders/**"), type = PolicyType.CUSTOM)
        val explanation = explainer().explainOf("/orders/1", SimplePrincipal("user", policies = setOf("no-orders")))
        explanation.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
        explanation.decidedBy.assert().isEqualTo(
            DecisionRef(ExplainLayer.PRINCIPAL_POLICY, "no-orders", 0, "/orders/**"),
        )
        explanation.overriddenAllows.assert().containsExactly(
            DecisionRef(ExplainLayer.GLOBAL_POLICY, "public", 0, "/orders/**"),
        )
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun explicitDenySkipsLaterLayers() {
        globalPolicies += policy("deny-admin", deny("/admin/**"))
        storedPolicies["admin"] = policy("admin", allow("/admin/**"), type = PolicyType.CUSTOM)
        val principal = SimplePrincipal("user", policies = setOf("admin"))
        val explanation = explainer().explainOf("/admin/users", principal)
        explanation.decidedBy?.policyId.assert().isEqualTo("deny-admin")
        explanation.layer(ExplainLayer.PRINCIPAL_POLICY).state.assert().isEqualTo(LayerState.NOT_EVALUATED)

        val exhaustive = explainer().explainOf("/admin/users", principal, ExplainOptions(exhaustive = true))
        val principalLayer = exhaustive.layer(ExplainLayer.PRINCIPAL_POLICY)
        principalLayer.state.assert().isEqualTo(LayerState.EVALUATED_AFTER_DECISION)
        principalLayer.result.assert().isEqualTo(VerifyResult.ALLOW)
        exhaustive.decidedBy?.policyId.assert().isEqualTo("deny-admin")
        exhaustive.warnings.assert().isEmpty()
    }

    @Test
    fun rolePermission() {
        val ship = PermissionData(id = "order.ship", name = "Ship", action = path("/orders/*/ship"))
        val cancel = PermissionData(id = "order.cancel", name = "Cancel", action = path("/orders/*/cancel"))
        appRolePermission = AppRolePermissionData(
            AppPermissionData("app", groups = listOf(PermissionGroupData("order", permissions = listOf(ship, cancel)))),
            listOf(RolePermissionData("clerk", setOf("order.cancel")), RolePermissionData("admin", setOf("*"))),
        )
        val explanation = explainer().explainOf(
            "/orders/1/ship",
            SimplePrincipal("user", roles = setOf("clerk", "admin"))
        )
        explanation.authorized.assert().isTrue()
        explanation.decidedBy.assert().isEqualTo(
            DecisionRef(
                ExplainLayer.ROLE_PERMISSION,
                statementName = "Ship",
                roleId = "admin",
                permissionId = "order.ship"
            ),
        )
        val roles = explanation.layer(ExplainLayer.ROLE_PERMISSION).roles
        roles.map { it.roleId }.assert().containsExactly("clerk", "admin")
        roles.first().permissions.single().outcome.assert().isEqualTo(StatementOutcome.ACTION_MISS)
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun appConditionMissSkipsPermissions() {
        val never = RecordingCondition(result = false)
        val ship = PermissionData(id = "order.ship", name = "Ship", action = path("/orders/*/ship"))
        appRolePermission = AppRolePermissionData(
            AppPermissionData("app", never, listOf(PermissionGroupData("order", permissions = listOf(ship)))),
            listOf(RolePermissionData("admin", setOf("*"))),
        )
        val explanation = explainer().explainOf("/orders/1/ship", SimplePrincipal("user", roles = setOf("admin")))
        explanation.authorized.assert().isFalse()
        val roleLayer = explanation.layer(ExplainLayer.ROLE_PERMISSION)
        roleLayer.conditionMatched.assert().isFalse()
        roleLayer.roles.single().permissions.single().outcome.assert().isEqualTo(StatementOutcome.NOT_EVALUATED)
    }

    @Test
    fun noMatch() {
        globalPolicies += policy("public", allow("/public/**"))
        val anonymous = explainer().explainOf("/private", SimplePrincipal.ANONYMOUS)
        anonymous.status.assert().isEqualTo(SecurityStatus.UNAUTHORIZED)
        anonymous.reason.assert().isEqualTo("Implicit Deny")
        anonymous.decidedBy.assert().isNull()
        anonymous.warnings.assert().isEmpty()
        explainer().explainOf("/private").status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
    }

    private val platformRoot = SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.PLATFORM)

    @Test
    fun root() {
        val explanation = explainer().explainOf("/anything", platformRoot)
        explanation.authorized.assert().isTrue()
        explanation.decidedBy.assert().isEqualTo(DecisionRef(ExplainLayer.ROOT))
        explanation.layers.map { it.layer }.assert().startsWith(ExplainLayer.BLACKLIST, ExplainLayer.ROOT)
        explanation.layer(ExplainLayer.BLACKLIST).result.assert().isEqualTo(VerifyResult.ALLOW)
        explanation.layer(ExplainLayer.GLOBAL_POLICY).state.assert().isEqualTo(LayerState.NOT_EVALUATED)
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun rootIdOutsideThePlatformTenantIsNotRoot() {
        val explanation = explainer().explainOf("/anything", SimplePrincipal(CoSecPrincipal.ROOT_ID))
        explanation.authorized.assert().isFalse()
        explanation.layer(ExplainLayer.ROOT).result.assert().isEqualTo(VerifyResult.IMPLICIT_DENY)
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun blacklistAppliesToRoot() {
        val blocked = object : BlacklistChecker {
            override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(false)
        }
        val explanation = explainer(blacklistChecker = blocked).explainOf("/anything", platformRoot)
        explanation.authorized.assert().isFalse()
        explanation.decidedBy.assert().isEqualTo(DecisionRef(ExplainLayer.BLACKLIST))
        explanation.layer(ExplainLayer.ROOT).state.assert().isEqualTo(LayerState.NOT_EVALUATED)
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun blacklist() {
        globalPolicies += policy("public", allow("/**"))
        val blocked = object : BlacklistChecker {
            override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(false)
        }
        val explanation = explainer(blacklistChecker = blocked).explainOf("/anything")
        explanation.authorized.assert().isFalse()
        explanation.decidedBy.assert().isEqualTo(DecisionRef(ExplainLayer.BLACKLIST))
        explanation.layer(ExplainLayer.GLOBAL_POLICY).state.assert().isEqualTo(LayerState.NOT_EVALUATED)
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun warnsAboutMissingPolicyAndAppPermission() {
        val principal = SimplePrincipal("user", policies = setOf("gone"), roles = setOf("clerk"))
        val explanation = explainer().explainOf("/orders", principal)
        explanation.warnings.map { it.code }.assert().containsExactlyInAnyOrder(
            ExplainWarningCode.MISSING_POLICY,
            ExplainWarningCode.NO_APP_PERMISSION,
        )
        explanation.layer(ExplainLayer.ROLE_PERMISSION).state.assert().isEqualTo(LayerState.EMPTY)
        // Authorization fails closed on the missing policy.
        explanation.status.assert().isEqualTo(SecurityStatus.ERROR)
        explanation.authorized.assert().isFalse()
    }

    @Test
    fun missingPolicyOverridesGlobalAllowWithoutInconsistency() {
        globalPolicies += policy("public", allow("/**"))
        val explanation = explainer().explainOf("/orders", SimplePrincipal("user", policies = setOf("gone")))
        explanation.status.assert().isEqualTo(SecurityStatus.ERROR)
        explanation.authorized.assert().isFalse()
        // The trace narrates the found policies (the global allow); real authorization aborted before deciding.
        explanation.warnings.map { it.code }.assert().containsExactly(ExplainWarningCode.MISSING_POLICY)
    }

    @Test
    fun warnsWhenNoRoleAppliesInTheSpace() {
        appRolePermission = AppRolePermissionData(
            AppPermissionData("app", groups = listOf(PermissionGroupData("all", permissions = listOf()))),
            listOf(RolePermissionData("admin", setOf("*"))),
        )
        // A team-a role, explained for a request in the default space.
        val explanation = explainer().explainOf("/orders", SimplePrincipal("user", roles = setOf("admin@team-a")))
        explanation.authorized.assert().isFalse()
        explanation.warnings.map { it.code }.assert().containsExactly(ExplainWarningCode.NO_ROLE_IN_SPACE)
        explanation.layer(ExplainLayer.ROLE_PERMISSION).state.assert().isEqualTo(LayerState.EMPTY)
    }

    @Test
    fun detailControlsConfiguration() {
        globalPolicies += policy("public", allow("/public/**"))
        val summary = explainer().explainOf("/public/a")
        summary.layer(ExplainLayer.GLOBAL_POLICY).policies.single().statements.single().action.configuration
            .assert().isNull()
        val full = explainer().explainOf("/public/a", options = ExplainOptions(detail = ExplainDetail.FULL))
        val action = full.layer(ExplainLayer.GLOBAL_POLICY).policies.single().statements.single().action
        action.type.assert().isEqualTo("path")
        action.configuration.assert().isNotNull()
    }

    @Test
    fun matcherErrorFailsClosedWithoutInconsistency() {
        val failing = RecordingCondition(error = IllegalStateException("boom"))
        globalPolicies += policy("public", allow("/public/**"), condition = failing)
        val explanation = explainer().explainOf("/public/a")
        explanation.status.assert().isEqualTo(SecurityStatus.ERROR)
        explanation.warnings.map { it.code }.assert().containsExactly(ExplainWarningCode.MATCHER_ERROR)
        explanation.layer(ExplainLayer.GLOBAL_POLICY).policies.single().conditionMatched.assert().isNull()
    }

    @Test
    fun runsAsPolicyEvaluation() {
        val recording = RecordingCondition()
        globalPolicies += policy("public", allow("/public/**"), condition = recording)
        explainer().explainOf("/public/a")
        // Once for the real decision, once for the trace; both flagged so custom matchers can skip side effects.
        recording.evaluations.assert().containsExactly(true, true)
    }

    @Test
    fun readsEachRepositoryOnce() {
        globalPolicies += policy("public", allow("/public/**"))
        storedPolicies["mine"] = policy("mine", allow("/mine/**"), type = PolicyType.CUSTOM)
        explainer().explainOf("/public/a", SimplePrincipal("user", policies = setOf("mine")))
        repositoryReads.get().assert().isEqualTo(2)
    }

    @Test
    fun reportsInconsistentTrace() {
        // Matches only on its first call: the real decision sees a match, the trace does not.
        val flaky = RecordingCondition(results = ArrayDeque(listOf(true, false)))
        globalPolicies += policy("public", allow("/public/**"), condition = flaky)
        val explanation = explainer().explainOf("/public/a")
        explanation.authorized.assert().isTrue()
        explanation.warnings.map { it.code }.assert().containsExactly(ExplainWarningCode.TRACE_INCONSISTENT)
    }

    @Test
    fun listsRateLimitRulesWithoutSpending() {
        globalPolicies += policy("public", allow("/**"))
        val rules = RateLimitRuleProvider.of(
            listOf(
                RateLimitRule(id = "public", action = path("/public/**"), rateLimit = RateLimit(1.0)),
                RateLimitRule(id = "orders", action = path("/orders/**"), rateLimit = RateLimit(1.0)),
            ),
        )
        explainer(
            rateLimitRuleProvider = rules
        ).explainOf("/public/a").rateLimitRules.assert().containsExactly("public")
    }

    @Test
    fun deniedRequestsListNoRateLimitRules() {
        val rules = RateLimitRuleProvider.of(
            listOf(RateLimitRule(id = "public", action = path("/public/**"), rateLimit = RateLimit(1.0))),
        )
        explainer(rateLimitRuleProvider = rules).explainOf("/public/a").rateLimitRules.assert().isEmpty()
    }

    private val tokenParser = SecurityContextParser { request ->
        when (request.getHeader("Authorization")) {
            "good" -> SimpleSecurityContext(SimplePrincipal("token-user", policies = setOf("mine")))
            "expired" -> throw TokenExpiredException()
            else -> SimpleSecurityContext.anonymous()
        }
    }

    private fun tokenExplainer() = AuthorizationExplainer(
        policyRepository,
        appRolePermissionRepository,
        securityContextParser = tokenParser,
    )

    private fun tokenRequest(token: String) =
        EvaluateRequest(path = "/mine/1", headers = mapOf("Authorization" to token))

    @Test
    fun explainsTheTokensPrincipal() {
        storedPolicies["mine"] = policy("mine", allow("/mine/**"), type = PolicyType.CUSTOM)
        val explanation = checkNotNull(tokenExplainer().explain(tokenRequest("good")).block())
        explanation.authorized.assert().isTrue()
        explanation.decidedBy?.policyId.assert().isEqualTo("mine")
        explanation.warnings.assert().isEmpty()
    }

    @Test
    fun rejectedTokenIsExplainedAsAnonymous() {
        storedPolicies["mine"] = policy("mine", allow("/mine/**"), type = PolicyType.CUSTOM)
        val explanation = checkNotNull(tokenExplainer().explain(tokenRequest("expired")).block())
        explanation.status.assert().isEqualTo(SecurityStatus.UNAUTHORIZED)
        explanation.reason.assert().isEqualTo("Token Expired")
        explanation.warnings.single().apply {
            code.assert().isEqualTo(ExplainWarningCode.TOKEN_INVALID)
            message.assert().contains("Token Expired")
        }
    }

    @Test
    fun tokenModeNeedsAParser() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            explainer().explain(tokenRequest("good"))
        }
    }

    private class RecordingCondition(
        private val result: Boolean = true,
        private val results: ArrayDeque<Boolean>? = null,
        private val error: RuntimeException? = null
    ) : ConditionMatcher {
        val evaluations = CopyOnWriteArrayList<Boolean>()
        override val type: String = "recording"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()

        override fun match(request: Request, securityContext: SecurityContext): Boolean {
            evaluations += securityContext.isPolicyEvaluation
            error?.let { throw it }
            return results?.removeFirst() ?: result
        }
    }
}
