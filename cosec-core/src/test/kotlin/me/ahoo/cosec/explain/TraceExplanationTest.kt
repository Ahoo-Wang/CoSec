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

import io.mockk.every
import io.mockk.mockkObject
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.EngineAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.authorization.engine.AppPermissionGroup
import me.ahoo.cosec.authorization.engine.PolicyGroup
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.GroupTrace
import me.ahoo.cosec.engine.GuardOutcome
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.RuleOutcome
import me.ahoo.cosec.engine.RuleTrace
import me.ahoo.cosec.engine.Trace
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.limiter.RateLimit
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.pipeline.SecurityDecision
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import me.ahoo.cosec.engine.LayerState as EngineLayerState
import me.ahoo.cosec.engine.LayerTrace as EngineLayerTrace

/**
 * The trace-based explanation (RFC 0002 §5.2) where the narration has nothing to compare it with: rule identity
 * across long grant lists and repeated grants, errors that end the evaluation, and traces the engine could not
 * complete.
 */
class TraceExplanationTest {
    private val globalPolicies = mutableListOf<Policy>()
    private val storedPolicies = mutableMapOf<String, Policy>()
    private var appRolePermission: AppRolePermission? = null
    private var globalError: Throwable? = null
    private var principalError: Throwable? = null

    private val policyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> =
            globalError?.let { Mono.error(it) } ?: Mono.just(globalPolicies.toList())

        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
            principalError?.let { Mono.error(it) } ?: Mono.just(policyIds.mapNotNull { storedPolicies[it] })

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
        condition: ConditionMatcher = AllConditionMatcher.INSTANCE,
        name: String = "name-$id"
    ) = PolicyData(id, "", name, "", type, "tenant", condition, statements.toList())

    private fun statement(name: String, pattern: String, effect: Effect = Effect.ALLOW) =
        StatementData(name = name, effect = effect, action = path(pattern))

    private fun permission(id: String, pattern: String = "/none", effect: Effect = Effect.ALLOW) =
        PermissionData(id = id, name = "name-$id", effect = effect, action = path(pattern))

    private fun grants(appPermission: AppPermission, vararg roles: Pair<String, Collection<String>>) {
        appRolePermission = AppRolePermissionData(
            appPermission,
            roles.map { (role, ids) -> RolePermissionData(role, ids.toCollection(LinkedHashSet())) },
        )
    }

    private fun catalog(vararg permissions: PermissionData) =
        AppPermissionData("app", groups = listOf(PermissionGroupData("g", permissions = permissions.toList())))

    private fun request(path: String) = EvaluateRequest(path = path, headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))

    private fun explain(
        path: String,
        principal: CoSecPrincipal = SimplePrincipal("user"),
        options: ExplainOptions = ExplainOptions(),
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp
    ): Explanation = checkNotNull(
        ExplainerImpl.TRACE.create(policyRepository, appRolePermissionRepository, blacklistChecker)
            .explain(request(path), principal, options)
            .block(),
    )

    /** The traces of the one evaluation an [EngineAuthorization] at [level] makes. */
    private fun evaluate(path: String, principal: CoSecPrincipal, level: TraceLevel): Evaluation {
        var evaluation: Evaluation? = null
        EngineAuthorization(
            policyRepository,
            appRolePermissionRepository,
            BlacklistChecker.NoOp,
            RootPrincipalMatcher.NONE,
            level,
        ) { evaluated, _ -> evaluation = evaluated }
            .decide(request(path), SimpleSecurityContext(principal))
            .onErrorResume { Mono.empty() }
            .block()
        return checkNotNull(evaluation)
    }

    private fun Explanation.layer(layer: ExplainLayer) = layers.single { it.layer == layer }

    /** Delegates to [AppPermissionData] without being one, so the adapter rebuilds its rules for every request. */
    private class OtherAppPermission(data: AppPermissionData) : AppPermission by data

    @ParameterizedTest
    @EnumSource(TraceLevel::class, names = ["FULL", "EXHAUSTIVE"])
    fun longGrantListsTraceWithoutContractErrors(level: TraceLevel) {
        // Positions 64 and above are memoized per request and per role, not shared (#994, #995).
        val ids = List(GRANTS) { "p$it" }
        val permissions = ids.map { permission(it, if (it == ids.last()) "/target" else "/p/$it") }.toTypedArray()
        val principal = SimplePrincipal("user", roles = setOf("r1", "r2"))
        listOf(catalog(*permissions), OtherAppPermission(catalog(*permissions))).forEach { appPermission ->
            grants(appPermission, "r1" to ids, "r2" to ids.reversed())
            val evaluation = evaluate("/target", principal, level)
            val groups = checkNotNull(evaluation.trace).layers.flatMap { it.groups }
            groups.map { it.contractError }.assert().allMatch { it == null }
            groups.single { it.id == "app" }.rules.size.assert().isEqualTo(2 * GRANTS)

            val explanation = explain("/target", principal, ExplainOptions(exhaustive = level == TraceLevel.EXHAUSTIVE))
            explanation.warnings.assert().isEmpty()
            explanation.decidedBy.assert().isEqualTo(
                DecisionRef(
                    ExplainLayer.ROLE_PERMISSION,
                    statementName = "name-p69",
                    roleId = "r1",
                    permissionId = "p69"
                ),
            )
            val (r1, r2) = explanation.layer(ExplainLayer.ROLE_PERMISSION).roles
            r1.permissions.map { it.index }.assert().isEqualTo((0 until GRANTS).toList())
            r1.permissions.last().apply {
                id.assert().isEqualTo("p69")
                outcome.assert().isEqualTo(StatementOutcome.MATCHED)
            }
            r2.permissions.first().apply {
                id.assert().isEqualTo("p69")
                index.assert().isEqualTo(0)
                outcome.assert().isEqualTo(StatementOutcome.MATCHED)
            }
            r2.permissions.drop(1).map { it.outcome }.assert().allMatch { it == StatementOutcome.ACTION_MISS }
        }
    }

    @Test
    fun permissionGrantedAtSeveralPositions() {
        val a = permission("a")
        val b = permission("b")
        val c = permission("c", "/c", Effect.DENY)
        grants(catalog(a, b, c), "r1" to listOf("a", "b", "c"), "r2" to listOf("c"), "r3" to listOf("*"))
        val explanation = explain("/c", SimplePrincipal("user", roles = setOf("r1", "r2", "r3")))
        explanation.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
        // The first position in grant order decides; every other position of `c` is its own statement.
        explanation.decidedBy.assert().isEqualTo(
            DecisionRef(ExplainLayer.ROLE_PERMISSION, statementName = "name-c", roleId = "r1", permissionId = "c"),
        )
        val roles = explanation.layer(ExplainLayer.ROLE_PERMISSION).roles
        roles.map { role -> role.roleId to role.permissions.map { "${it.id}@${it.index}:${it.outcome}" } }
            .assert().containsExactly(
                "r1" to listOf("a@0:ACTION_MISS", "b@1:ACTION_MISS", "c@2:MATCHED"),
                "r2" to listOf("c@0:MATCHED"),
                "r3" to listOf("a@0:ACTION_MISS", "b@1:ACTION_MISS", "c@2:MATCHED"),
            )
    }

    @Test
    fun allowsAfterADenyAreEvaluatedForTheTrace() {
        grants(catalog(permission("a", "/x"), permission("d", "/x", Effect.DENY)), "r1" to listOf("a", "d"))
        val roles = explain("/x", SimplePrincipal("user", roles = setOf("r1")))
            .layer(ExplainLayer.ROLE_PERMISSION).roles
        roles.single().permissions.map { it.outcome }
            .assert().containsExactly(StatementOutcome.MATCHED, StatementOutcome.MATCHED)
    }

    @Test
    fun statementsAndPoliciesKeepTheirPositions() {
        // Two policies share an id, and a policy repeats a statement name: the refs tell them apart.
        globalPolicies += policy("same", statement("s", "/x"), statement("s", "/x/**"), name = "first")
        globalPolicies += policy("same", statement("t", "/y"), statement("s", "/*", Effect.DENY), name = "second")
        val explanation = explain("/x", options = ExplainOptions(detail = ExplainDetail.FULL))
        explanation.decidedBy.assert().isEqualTo(DecisionRef(ExplainLayer.GLOBAL_POLICY, "same", 1, "s"))
        val policies = explanation.layer(ExplainLayer.GLOBAL_POLICY).policies
        policies.map { it.name }.assert().containsExactly("first", "second")
        policies.map { policy ->
            policy.statements.map { "${it.index}:${it.name}:${it.action.pattern()}:${it.effect}:${it.outcome}" }
        }.assert().containsExactly(
            listOf("0:s:/x:ALLOW:MATCHED", "1:s:/x/**:ALLOW:MATCHED"),
            listOf("0:t:/y:ALLOW:ACTION_MISS", "1:s:/*:DENY:MATCHED"),
        )
    }

    private fun MatcherRef.pattern(): String? = configuration?.asString()

    @Test
    fun failedLayerEndsTheEvaluation() {
        globalPolicies += policy("broken", statement("s", "/x"), condition = ThrowingCondition)
        storedPolicies["mine"] = policy("mine", statement("s", "/x"), type = PolicyType.CUSTOM)
        grants(catalog(permission("a", "/x")), "r1" to listOf("a"))
        val principal = SimplePrincipal("user", policies = setOf("mine"), roles = setOf("r1"))

        val explanation = explain("/x", principal)
        explanation.status.assert().isEqualTo(SecurityStatus.ERROR)
        explanation.decidedBy.assert().isNull()
        explanation.warnings.single().apply {
            code.assert().isEqualTo(ExplainWarningCode.MATCHER_ERROR)
            message.assert().contains("Matching policy [broken] condition failed", "boom")
        }
        explanation.layer(ExplainLayer.GLOBAL_POLICY).apply {
            state.assert().isEqualTo(LayerState.EVALUATED)
            // Indeterminate: no result, whatever matched.
            result.assert().isNull()
            policies.single().conditionMatched.assert().isNull()
            policies.single().statements.single().outcome.assert().isEqualTo(StatementOutcome.NOT_EVALUATED)
        }
        explanation.layer(ExplainLayer.PRINCIPAL_POLICY).state.assert().isEqualTo(LayerState.NOT_EVALUATED)
        explanation.layer(ExplainLayer.ROLE_PERMISSION).state.assert().isEqualTo(LayerState.NOT_EVALUATED)

        val exhaustive = explain("/x", principal, ExplainOptions(exhaustive = true))
        exhaustive.layer(ExplainLayer.PRINCIPAL_POLICY).apply {
            state.assert().isEqualTo(LayerState.EVALUATED_AFTER_DECISION)
            result.assert().isEqualTo(VerifyResult.ALLOW)
        }
        exhaustive.layer(ExplainLayer.ROLE_PERMISSION).apply {
            state.assert().isEqualTo(LayerState.EVALUATED_AFTER_DECISION)
            result.assert().isEqualTo(VerifyResult.ALLOW)
        }
    }

    @Test
    fun ruleErrorsWarnEvenAfterTheDecision() {
        val failing = PermissionData(id = "f", name = "f", action = path("/x"), condition = ThrowingCondition)
        grants(catalog(permission("d", "/x", Effect.DENY), failing), "r1" to listOf("d", "f"))
        val explanation = explain("/x", SimplePrincipal("user", roles = setOf("r1")))
        // The deny decides; the failing allow is evaluated only for the trace.
        explanation.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
        explanation.warnings.single().message.assert().contains("Matching role [r1] permission [f] failed", "boom")
        explanation.layer(ExplainLayer.ROLE_PERMISSION).roles.single().permissions.last().apply {
            outcome.assert().isEqualTo(StatementOutcome.ERROR)
            error.assert().contains("boom")
        }
    }

    @Test
    fun missingPermissionWarnsWhereTheEvaluationFails() {
        grants(
            catalog(permission("a", "/x"), permission("b", "/x")),
            "r1" to listOf("a", "ghost", "b", "phantom"),
        )
        val principal = SimplePrincipal("user", roles = setOf("r1"))
        val explanation = explain("/x", principal)
        explanation.status.assert().isEqualTo(SecurityStatus.ERROR)
        explanation.warnings.single().apply {
            code.assert().isEqualTo(ExplainWarningCode.MISSING_PERMISSION)
            message.assert().isEqualTo(
                "Role [r1] is granted permissions [ghost, phantom], which app [app] does not define; " +
                    "authorization fails closed.",
            )
        }
        // The unresolved grant is no statement; the defined ones are counted without it.
        explanation.layer(ExplainLayer.ROLE_PERMISSION).roles.single().permissions.map { "${it.id}@${it.index}" }
            .assert().containsExactly("a@0", "b@1")

        // A deny before it decides, so the evaluation never fails on it.
        grants(catalog(permission("d", "/x", Effect.DENY)), "r1" to listOf("d", "ghost"))
        explain("/x", principal).apply {
            status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
            warnings.assert().isEmpty()
        }
    }

    @Test
    fun missingPolicyAfterTheDecision() {
        globalPolicies += policy("deny", statement("s", "/x", Effect.DENY))
        val principal = SimplePrincipal("user", policies = setOf("gone"))
        explain("/x", principal).apply {
            status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
            layer(ExplainLayer.PRINCIPAL_POLICY).state.assert().isEqualTo(LayerState.NOT_EVALUATED)
            warnings.assert().isEmpty()
        }
        explain("/x", principal, ExplainOptions(exhaustive = true)).apply {
            status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
            layer(ExplainLayer.PRINCIPAL_POLICY).state.assert().isEqualTo(LayerState.EVALUATED_AFTER_DECISION)
            // The request was denied (403); the missing policy fails only requests that reach it.
            warnings.single().apply {
                code.assert().isEqualTo(ExplainWarningCode.MISSING_POLICY)
                message.assert().isEqualTo(
                    "The principal lists policy [gone], which the repository does not have; authorization fails " +
                        "closed on any request that reaches the principal's policies.",
                )
            }
        }
    }

    @Test
    fun unavailableLayerFailsTheExplanationAsTheNarrationDoes() {
        val unavailable = IllegalStateException("redis down")
        globalError = unavailable
        ExplainerImpl.entries.forEach { impl ->
            impl.create(policyRepository, appRolePermissionRepository)
                .explain(request("/x"), SimplePrincipal("user"))
                .test()
                .expectErrorMatches { it === unavailable }
                .verify()
        }
    }

    @Test
    fun unavailableLayerAfterADenyFailsOnlyWhereItIsEvaluated() {
        globalPolicies += policy("deny", statement("s", "/x", Effect.DENY))
        val unavailable = IllegalStateException("redis down")
        principalError = unavailable
        val principal = SimplePrincipal("user", policies = setOf("mine"))
        // The deny ends the evaluation before the principal layer, which FULL never reads from the trace.
        explain("/x", principal).apply {
            status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
            layer(ExplainLayer.PRINCIPAL_POLICY).state.assert().isEqualTo(LayerState.NOT_EVALUATED)
        }
        ExplainerImpl.TRACE.create(policyRepository, appRolePermissionRepository)
            .explain(request("/x"), principal, ExplainOptions(exhaustive = true))
            .test()
            .expectErrorMatches { it === unavailable }
            .verify()
    }

    @Test
    fun failedBlacklistCheckFailsTheExplanationAsTheNarrationDoes() {
        val down = IllegalStateException("blacklist down")
        val failing = object : BlacklistChecker {
            override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.error(down)
        }
        ExplainerImpl.entries.forEach { impl ->
            impl.create(policyRepository, appRolePermissionRepository, failing)
                .explain(request("/x"), SimplePrincipal("user"))
                .test()
                .expectErrorMatches { it === down }
                .verify()
        }
    }

    @Test
    fun errorBeforeTheDecisionFailsTheExplanationAsTheNarrationDoes() {
        // An Error is not an engine failure: the engine lets it through, and authorization fails with it (500).
        val error = NotImplementedError("not yet")
        val throwing = object : ConditionMatcher by ThrowingCondition {
            override fun match(request: Request, securityContext: SecurityContext): Boolean = throw error
        }
        globalPolicies += policy("broken", statement("s", "/x"), condition = throwing)
        ExplainerImpl.entries.forEach { impl ->
            impl.create(policyRepository, appRolePermissionRepository)
                .explain(request("/x"), SimplePrincipal("user"))
                .test()
                .expectErrorMatches { it === error }
                .verify()
        }
    }

    @Test
    fun unparsableContextFailsTheExplanation() {
        val broken = IllegalArgumentException("bad header")
        ExplainerImpl.TRACE.create(
            policyRepository,
            appRolePermissionRepository,
            securityContextParser = { throw broken },
        ).explain(request("/x")).test().expectErrorMatches { it === broken }.verify()
    }

    @Test
    fun brokenEngineFailsTheExplanation() {
        globalPolicies += policy("public", statement("s", "/x"))
        val broken = IllegalStateException("adapter contract")
        mockkObject(CoSecEngine) {
            every { CoSecEngine.evaluate(any(), any(), any()) } throws broken
            explain().test().expectErrorMatches { it === broken }.verify()
        }
    }

    @Test
    fun failedEnforcementFailsTheExplanation() {
        // The engine allows, but recording the verify context fails, so authorization answers 500.
        globalPolicies += policy("public", statement("s", "/x"))
        val recording = IllegalStateException("context is read-only")
        val parser = SecurityContextParser {
            val base = SimpleSecurityContext(SimplePrincipal("user"))
            object : SecurityContext by base {
                override fun setAttributeValue(attributeKey: String, value: Any): SecurityContext {
                    if (value is VerifyContext) {
                        throw recording
                    }
                    base.setAttributeValue(attributeKey, value)
                    return this
                }
            }
        }
        ExplainerImpl.TRACE.create(policyRepository, appRolePermissionRepository, securityContextParser = parser)
            .explain(request("/x"))
            .test()
            .expectErrorMatches { it === recording }
            .verify()
    }

    private fun explain() = ExplainerImpl.TRACE.create(policyRepository, appRolePermissionRepository)
        .explain(request("/x"), SimplePrincipal("user"))

    @Test
    fun explanationMustNameWhatWasEnforced() {
        globalPolicies += policy("public", statement("s", "/x"))
        val evaluation = evaluate("/x", SimplePrincipal("user"), TraceLevel.FULL)
        val policies = PolicySet(
            listOf(LayerInput.Loaded(LayerKind.GLOBAL, globalPolicies.map { PolicyGroup(it) })),
        )
        val traced = TracedEvaluation(evaluation, policies)
        val allowed = SecurityDecision(SecurityStatus.ALLOWED, AuthorizeResult.ALLOW, request("/x"))
        // Allowed without the verify context the engine decided by.
        assertThrows<IllegalStateException> { TraceExplanations.explain(allowed, traced, ExplainDetail.SUMMARY) }
            .message.assert().contains("enforced [null]")
        // Failed, although the engine decided.
        val failed = SecurityDecision(SecurityStatus.ERROR, AuthorizeResult.IMPLICIT_DENY, request("/x"))
        assertThrows<IllegalStateException> { TraceExplanations.explain(failed, traced, ExplainDetail.SUMMARY) }
            .message.assert().contains("Authorization failed")
    }

    @Test
    fun root() {
        val root = SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.PLATFORM)
        val explanation = checkNotNull(
            ExplainerImpl.TRACE.create(
                policyRepository,
                appRolePermissionRepository,
                rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT
            )
                .explain(request("/x"), root)
                .block(),
        )
        explanation.layer(ExplainLayer.ROOT).apply {
            state.assert().isEqualTo(LayerState.EVALUATED)
            result.assert().isEqualTo(VerifyResult.ALLOW)
            matched.assert().isEqualTo(DecisionRef(ExplainLayer.ROOT))
        }
        explanation.decidedBy.assert().isEqualTo(DecisionRef(ExplainLayer.ROOT))
    }

    @Test
    fun repeatedRoleIsListedPerGrant() {
        // A custom repository may list a role twice; each grant keeps its own statements, as in the narration.
        grants(catalog(permission("a", "/x"), permission("b")), "r1" to listOf("a"), "r1" to listOf("b", "a"))
        val principal = SimplePrincipal("user", roles = setOf("r1"))
        val explanations = ExplainerImpl.entries.map { impl ->
            checkNotNull(
                impl.create(policyRepository, appRolePermissionRepository).explain(request("/x"), principal).block()
            )
        }
        val roles = explanations.map { it.layer(ExplainLayer.ROLE_PERMISSION).roles }
        roles.first().map { role -> role.roleId to role.permissions.map { "${it.id}@${it.index}" } }
            .assert().containsExactly("r1" to listOf("a@0"), "r1" to listOf("b@0", "a@1"))
        roles.last().assert().isEqualTo(roles.first())
    }

    @Test
    fun failingRateLimitRuleWarns() {
        globalPolicies += policy("public", statement("s", "/x"))
        val rules = RateLimitRuleProvider.of(
            listOf(
                RateLimitRule(
                    id = "broken",
                    action = path("/x"),
                    rateLimit = RateLimit(1.0),
                    condition = ThrowingCondition
                ),
                RateLimitRule(id = "x", action = path("/x"), rateLimit = RateLimit(1.0)),
            ),
        )
        val explanation = checkNotNull(
            ExplainerImpl.TRACE.create(policyRepository, appRolePermissionRepository, rateLimitRuleProvider = rules)
                .explain(request("/x"), SimplePrincipal("user"))
                .block(),
        )
        explanation.authorized.assert().isTrue()
        explanation.rateLimitRules.assert().containsExactly("x")
        explanation.warnings.single().apply {
            code.assert().isEqualTo(ExplainWarningCode.MATCHER_ERROR)
            message.assert().contains("Matching rate limit rule [broken] failed", "boom")
        }
    }

    @Test
    fun incompleteTraceWarns() {
        val policy = policy("p", statement("s", "/x"))
        val policies = PolicySet(listOf(LayerInput.Loaded(LayerKind.GLOBAL, listOf(PolicyGroup(policy)))))
        val broken = IllegalStateException("misaligned")
        val trace = Trace(
            blocked = false,
            root = false,
            layers = listOf(
                EngineLayerTrace(
                    LayerKind.GLOBAL,
                    EngineLayerState.EVALUATED,
                    groups = listOf(GroupTrace("p", GuardOutcome.MATCHED, null, emptyList(), broken)),
                ),
            ),
        )
        val decision = SecurityDecision(
            SecurityStatus.FORBIDDEN,
            AuthorizeResult.IMPLICIT_DENY,
            request("/x"),
            SimpleSecurityContext(SimplePrincipal("user")),
        )
        val explanation = TraceExplanations.explain(
            decision,
            TracedEvaluation(Evaluation(Decision.Deny(null), trace), policies),
            ExplainDetail.SUMMARY,
        )
        explanation.warnings.single().apply {
            code.assert().isEqualTo(ExplainWarningCode.MATCHER_ERROR)
            message.assert().contains("Tracing [p] failed", "misaligned")
        }
        explanation.layer(ExplainLayer.GLOBAL_POLICY).policies.single().statements.assert().isEmpty()
        // The principal and role layers were not in the evaluated set.
        explanation.layer(ExplainLayer.PRINCIPAL_POLICY).state.assert().isEqualTo(LayerState.NOT_EVALUATED)
    }

    @Test
    fun incompleteRoleTraceWarns() {
        val appPermission = catalog(permission("a"), permission("b"))
        val group = AppPermissionGroup(appPermission.engineRules(), listOf(RolePermissionData("r1", setOf("a", "b"))))
        val policies = PolicySet(listOf(LayerInput.Loaded(LayerKind.ROLE, listOf(group))))
        // The engine could record only the first entry.
        val recorded = RuleTrace(
            StatementRef.RolePermission("app", "r1", "a", "name-a"),
            me.ahoo.cosec.model.Effect.ALLOW,
            RuleOutcome.ACTION_MISS,
        )
        val trace = Trace(
            blocked = false,
            root = false,
            layers = listOf(
                EngineLayerTrace(
                    LayerKind.ROLE,
                    EngineLayerState.EVALUATED,
                    groups = listOf(
                        GroupTrace(
                            "app",
                            GuardOutcome.MATCHED,
                            null,
                            listOf(recorded),
                            IllegalStateException("broken")
                        ),
                    ),
                ),
            ),
        )
        val decision = SecurityDecision(
            SecurityStatus.FORBIDDEN,
            AuthorizeResult.IMPLICIT_DENY,
            request("/x"),
            SimpleSecurityContext(SimplePrincipal("user", roles = setOf("r1"))),
        )
        val explanation = TraceExplanations.explain(
            decision,
            TracedEvaluation(Evaluation(Decision.Deny(null), trace), policies),
            ExplainDetail.SUMMARY,
        )
        explanation.warnings.single().apply {
            code.assert().isEqualTo(ExplainWarningCode.MATCHER_ERROR)
            message.assert().contains("Tracing [app] failed", "broken")
        }
        explanation.layer(ExplainLayer.ROLE_PERMISSION).roles.single().apply {
            roleId.assert().isEqualTo("r1")
            permissions.map { it.id }.assert().containsExactly("a")
        }
    }

    private object ThrowingCondition : ConditionMatcher {
        override val type: String = "throwing"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()

        override fun match(request: Request, securityContext: SecurityContext): Boolean =
            throw IllegalStateException("boom")
    }

    companion object {
        private const val GRANTS = 70
    }
}
