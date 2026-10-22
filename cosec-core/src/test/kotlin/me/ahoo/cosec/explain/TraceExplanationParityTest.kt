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
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
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
import me.ahoo.cosec.policy.condition.CONDITION_MATCHER_NEGATE_KEY
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import kotlin.random.Random

/**
 * Over random policies, grants, spaces, requests, failing matchers, regex timeouts, custom statements, blacklisted
 * and root principals, the explanation from the engine's trace is the narration's, field by field, wherever RFC 0002
 * X5 and X6 do not apply. Where they apply, the status, the result and the deciding rule still agree, and so does
 * everything up to the layer that failed.
 */
class TraceExplanationParityTest {
    private val patterns = listOf("/a/**", "/a/b/**", "/b/**", "/a/*/c", "/**")
    private val paths = listOf("/a/x", "/a/b/c", "/b/y", "/a/z/c", "/c")
    private val roles = listOf("r1", "r2", "r3")
    private val never = AllConditionMatcher(mapOf(CONDITION_MATCHER_NEGATE_KEY to true).asConfiguration())

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun Random.effect() = if (nextInt(4) == 0) Effect.DENY else Effect.ALLOW

    /** Mostly always true; sometimes never, sometimes failing: a matcher error (500) or a regex timeout (401 / 403). */
    private fun Random.condition(): ConditionMatcher = when (nextInt(40)) {
        in 0..7 -> never
        8 -> Throwing(IllegalStateException("boom"))
        9 -> Throwing(RegexTimeoutException("slow"))
        else -> AllConditionMatcher.INSTANCE
    }

    private fun Random.statement(index: Int): Statement {
        val name = if (nextInt(6) == 0) "custom$index" else "s$index"
        val statement = StatementData(
            name = name,
            effect = effect(),
            action = path(patterns.random(this)),
            condition = if (nextInt(4) == 0) condition() else AllConditionMatcher.INSTANCE,
        )
        return if (name.startsWith("custom")) CustomStatement(statement) else statement
    }

    private fun Random.policy(id: String, type: PolicyType): Policy = PolicyData(
        id = id,
        category = "",
        name = "name-$id",
        description = "",
        type = type,
        tenantId = "tenant",
        condition = if (nextInt(3) == 0) condition() else AllConditionMatcher.INSTANCE,
        statements = List(nextInt(4)) { statement(it) },
    )

    /** Grants may name "ghost", which the catalog does not define, and the same permission in several roles. */
    private fun Random.appRolePermission(): AppRolePermission {
        val permissions = List(nextInt(5)) { index ->
            PermissionData(
                id = "perm$index",
                name = "perm$index",
                effect = effect(),
                action = path(patterns.random(this)),
                condition = if (nextInt(6) == 0) condition() else AllConditionMatcher.INSTANCE,
            )
        }
        val ids = permissions.map { it.id } + if (nextInt(3) == 0) listOf("ghost") else emptyList()
        return AppRolePermissionData(
            AppPermissionData("app", condition(), listOf(PermissionGroupData("g", permissions = permissions))),
            roles.map { role ->
                val granted = if (nextInt(5) == 0) listOf("*") else ids.shuffled(this).filter { nextBoolean() }
                RolePermissionData(role, granted.toCollection(LinkedHashSet()))
            },
        )
    }

    private class Case(
        val policyRepository: PolicyRepository,
        val appRolePermissionRepository: AppRolePermissionRepository,
        val blacklistChecker: BlacklistChecker,
        val principal: CoSecPrincipal,
        val request: EvaluateRequest,
        val options: ExplainOptions
    ) {
        fun explain(impl: ExplainerImpl): Explanation = checkNotNull(
            impl.create(policyRepository, appRolePermissionRepository, blacklistChecker, RootPrincipalMatcher.DEFAULT)
                .explain(request, principal, options)
                .block(),
        )
    }

    private fun Random.case(): Case {
        val globalPolicies = List(nextInt(3)) { policy("g$it", PolicyType.GLOBAL) }
        val customPolicies = List(nextInt(3)) { policy("p$it", PolicyType.CUSTOM) }
        val appRolePermission = if (nextInt(6) == 0) null else appRolePermission()
        val policyRepository = object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(globalPolicies)
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
                Mono.just(customPolicies.filter { it.id in policyIds })

            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        }
        val appRolePermissionRepository = object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<RoleId>
            ): Mono<AppRolePermission> = Mono.justOrEmpty(appRolePermission)
        }
        val blocked = nextInt(20) == 0
        val blacklistChecker = object : BlacklistChecker {
            override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(!blocked)
        }
        val policyIds = customPolicies.filter { nextBoolean() }.map { it.id } +
            if (nextInt(8) == 0) listOf("gone") else emptyList()
        val principalRoles = roles.filter { nextBoolean() }.map { if (nextInt(4) == 0) "$it@team-a" else it }
        val user = SimplePrincipal(
            id = if (nextInt(20) == 0) CoSecPrincipal.ROOT_ID else "user",
            policies = policyIds.toSet(),
            roles = principalRoles.toSet(),
        )
        val headers = buildMap {
            put(AppIdCapable.APP_ID_KEY, "app")
            if (nextInt(4) == 0) put(SpaceIdCapable.SPACE_ID_KEY, "team-a")
        }
        return Case(
            policyRepository,
            appRolePermissionRepository,
            blacklistChecker,
            SimpleTenantPrincipal(user, SimpleTenant.PLATFORM),
            EvaluateRequest(path = paths.random(this), headers = headers),
            ExplainOptions(
                exhaustive = nextBoolean(),
                detail = if (nextBoolean()) ExplainDetail.FULL else ExplainDetail.SUMMARY,
            ),
        )
    }

    private enum class Kind { SAME, FAILED, SKIPPED_LAYERS, MISSING_POLICY, BLACKLIST, ROOT }

    /** Layers after the decision that would have failed, so they have no result. */
    private var indeterminate = 0

    @Test
    fun traceExplainsAsTheNarrationDoes() {
        val random = Random(SEED)
        val kinds = mutableMapOf<Kind, Int>()
        val statuses = mutableSetOf<SecurityStatus>()
        repeat(ITERATIONS) { iteration ->
            val case = random.case()
            val narrated = case.explain(ExplainerImpl.NARRATION).normalized()
            val traced = case.explain(ExplainerImpl.TRACE).normalized()
            val described = "iteration $iteration"
            traced.status.assert().describedAs(described).isEqualTo(narrated.status)
            traced.authorized.assert().describedAs(described).isEqualTo(narrated.authorized)
            traced.reason.assert().describedAs(described).isEqualTo(narrated.reason)
            traced.decidedBy.assert().describedAs(described).isEqualTo(narrated.decidedBy)
            val failing = traced.layers.indexOfFirst { it.state == LayerState.EVALUATED && it.result == null }
            val kind = when {
                failing >= 0 -> {
                    statuses += traced.status
                    assertFailed(case, traced, narrated, failing, described)
                    Kind.FAILED
                }

                traced.decidedBy?.layer == ExplainLayer.BLACKLIST -> Kind.BLACKLIST
                traced.decidedBy?.layer == ExplainLayer.ROOT -> Kind.ROOT
                else -> assertDecided(case, traced, narrated, described)
            }
            if (kind == Kind.BLACKLIST || kind == Kind.ROOT) {
                traced.assert().describedAs(described).isEqualTo(narrated)
            }
            kinds.merge(kind, 1, Int::plus)
        }
        // Each kind of case must actually occur for the comparison to mean anything.
        kinds[Kind.SAME].assert().isGreaterThan(ITERATIONS / 3)
        kinds[Kind.FAILED].assert().isGreaterThan(ITERATIONS / 20)
        kinds.keys.assert().containsAll(Kind.entries)
        // Failures that are not errors: a regex timeout is a deny (403 for the authenticated corpus, RFC 0002 §3.5).
        statuses.assert().contains(SecurityStatus.ERROR, SecurityStatus.FORBIDDEN)
        indeterminate.assert().isPositive()
    }

    /**
     * X6: the failure ends the evaluation, so the trace shows the layers up to the one that failed, and that one
     * without a result; the narration goes on.
     */
    private fun assertFailed(case: Case, traced: Explanation, narrated: Explanation, failing: Int, described: String) {
        val missing = "gone" in case.principal.policies
        for (index in 0..failing) {
            val trace = traced.layers[index]
            val narration = narrated.layers[index]
            when {
                // The missing policies end the layer before its conditions; the narration shows the policies found.
                missing && trace.layer == ExplainLayer.PRINCIPAL_POLICY -> Unit
                index == failing ->
                    trace.assert().describedAs(described).isEqualTo(narration.copy(result = null, matched = null))

                else -> trace.assert().describedAs(described).isEqualTo(narration)
            }
        }
        traced.layers.drop(failing + 1).map { it.state }.assert().describedAs(described).allMatch {
            it == LayerState.NOT_EVALUATED || it == LayerState.EVALUATED_AFTER_DECISION || it == LayerState.EMPTY
        }
        // The narration may even report an allow that a deny after the failure overrode.
        traced.overriddenAllows.assert().describedAs(described).isEmpty()
        narrated.warnings.assert().describedAs(described).containsAll(traced.warnings)
        traced.warnings.assert().describedAs(described).isNotEmpty()
    }

    /** Compares a decision that did not fail, and returns its kind. */
    private fun assertDecided(case: Case, traced: Explanation, narrated: Explanation, described: String): Kind {
        // X6: a principal policy that is missing ends the evaluation at its layer before any condition, so the
        // trace shows none of the layer's policies, where the narration shows those it found. And a layer that
        // would fail (exhaustively, after the decision) has no result, where the narration shows what matched.
        val missing = "gone" in case.principal.policies
        traced.layers.zip(narrated.layers).forEach { (trace, narration) ->
            when {
                missing && trace.layer == ExplainLayer.PRINCIPAL_POLICY -> Unit
                trace.state == LayerState.EVALUATED_AFTER_DECISION && trace.result == null -> {
                    indeterminate++
                    trace.assert().describedAs(described).isEqualTo(narration.copy(result = null, matched = null))
                }

                else -> trace.assert().describedAs(described).isEqualTo(narration)
            }
        }
        traced.overriddenAllows.assert().describedAs(described).isEqualTo(narrated.overriddenAllows)
        traced.rateLimitRules.assert().describedAs(described).isEqualTo(narrated.rateLimitRules)
        if (!case.options.exhaustive && traced.layers.any { it.state == LayerState.NOT_EVALUATED }) {
            // An explicit deny skipped the later layers: the narration still read them and warned about their data.
            narrated.warnings.assert().describedAs(described).containsAll(traced.warnings)
            return Kind.SKIPPED_LAYERS
        }
        if (missing) {
            // The narration also warns about the principal policies it found and the trace never evaluated.
            narrated.warnings.assert().describedAs(described).containsAll(traced.warnings)
            return Kind.MISSING_POLICY
        }
        // The narration warns in the order its parallel reads answer, the trace in layer order.
        traced.warnings.assert().describedAs(described).containsExactlyInAnyOrderElementsOf(narrated.warnings)
        return Kind.SAME
    }

    /**
     * What the two may word or classify differently by design: the trace words a missing policy after the decision
     * for what it is, and cannot tell an action miss from a condition miss for a custom statement (X5).
     */
    private fun Explanation.normalized(): Explanation = copy(
        warnings = warnings.map {
            if (it.code == ExplainWarningCode.MISSING_POLICY) it.copy(message = "") else it
        },
        layers = layers.map { layer ->
            layer.copy(
                policies = layer.policies.map { policy ->
                    policy.copy(
                        statements = policy.statements.map { statement ->
                            if (statement.name.startsWith("custom") && statement.outcome == StatementOutcome.ACTION_MISS) {
                                statement.copy(outcome = StatementOutcome.CONDITION_MISS)
                            } else {
                                statement
                            }
                        },
                    )
                },
            )
        },
    )

    /** Verifies as [StatementData] does, without being one, so the adapter calls [Statement.verify] (X5). */
    private class CustomStatement(statement: StatementData) : Statement by statement

    private class Throwing(private val error: RuntimeException) : ConditionMatcher {
        override val type: String = "throwing"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()

        override fun match(request: Request, securityContext: SecurityContext): Boolean = throw error
    }

    companion object {
        private const val SEED = 20261021
        private const val ITERATIONS = 2_000
    }
}
