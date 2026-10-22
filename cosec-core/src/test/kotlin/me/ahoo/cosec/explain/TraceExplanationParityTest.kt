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

import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
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
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import kotlin.random.Random

/**
 * Over random policies, grants, spaces and requests, the explanation from the engine's trace is the narration's,
 * field by field, wherever RFC 0002 X6 does not apply. Where it applies (a failed decision, a missing principal
 * policy, warnings about layers an explicit deny skipped), the status, the result and the deciding rule still agree.
 */
class TraceExplanationParityTest {
    private val patterns = listOf("/a/**", "/a/b/**", "/b/**", "/a/*/c", "/**")
    private val paths = listOf("/a/x", "/a/b/c", "/b/y", "/a/z/c", "/c")
    private val roles = listOf("r1", "r2", "r3")
    private val never = AllConditionMatcher(mapOf(CONDITION_MATCHER_NEGATE_KEY to true).asConfiguration())

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun Random.effect() = if (nextInt(4) == 0) Effect.DENY else Effect.ALLOW

    private fun Random.condition() = if (nextInt(5) == 0) never else AllConditionMatcher.INSTANCE

    private fun Random.policy(id: String, type: PolicyType): Policy = PolicyData(
        id = id,
        category = "",
        name = "name-$id",
        description = "",
        type = type,
        tenantId = "tenant",
        condition = condition(),
        statements = List(nextInt(4)) { index ->
            StatementData(name = "s$index", effect = effect(), action = path(patterns.random(this)))
        },
    )

    /** Grants may name "ghost", which the catalog does not define, and the same permission in several roles. */
    private fun Random.appRolePermission(): AppRolePermission {
        val permissions = List(nextInt(5)) { index ->
            PermissionData(
                id = "perm$index",
                name = "perm$index",
                effect = effect(),
                action = path(patterns.random(this))
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
        val principal: SimplePrincipal,
        val request: EvaluateRequest,
        val options: ExplainOptions
    ) {
        fun explain(impl: ExplainerImpl): Explanation = checkNotNull(
            impl.create(policyRepository, appRolePermissionRepository).explain(request, principal, options).block(),
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
        val policyIds = customPolicies.filter { nextBoolean() }.map { it.id } +
            if (nextInt(8) == 0) listOf("gone") else emptyList()
        val principalRoles = roles.filter { nextBoolean() }.map { if (nextInt(4) == 0) "$it@team-a" else it }
        val headers = buildMap {
            put(AppIdCapable.APP_ID_KEY, "app")
            if (nextInt(4) == 0) put(SpaceIdCapable.SPACE_ID_KEY, "team-a")
        }
        return Case(
            policyRepository,
            appRolePermissionRepository,
            SimplePrincipal(id = "user", policies = policyIds.toSet(), roles = principalRoles.toSet()),
            EvaluateRequest(path = paths.random(this), headers = headers),
            ExplainOptions(
                exhaustive = nextBoolean(),
                detail = if (nextBoolean()) ExplainDetail.FULL else ExplainDetail.SUMMARY,
            ),
        )
    }

    private enum class Kind { SAME, FAILED, SKIPPED_LAYERS, MISSING_POLICY }

    /** Layers after the decision that would have failed, so they have no result. */
    private var indeterminate = 0

    @Test
    fun traceExplainsAsTheNarrationDoes() {
        val random = Random(SEED)
        val kinds = mutableMapOf<Kind, Int>()
        repeat(ITERATIONS) { iteration ->
            val case = random.case()
            val narrated = case.explain(ExplainerImpl.NARRATION)
            val traced = case.explain(ExplainerImpl.TRACE)
            val described = "iteration $iteration"
            traced.status.assert().describedAs(described).isEqualTo(narrated.status)
            traced.authorized.assert().describedAs(described).isEqualTo(narrated.authorized)
            traced.reason.assert().describedAs(described).isEqualTo(narrated.reason)
            traced.decidedBy.assert().describedAs(described).isEqualTo(narrated.decidedBy)
            val kind = if (narrated.status == SecurityStatus.ERROR) {
                assertFailed(traced, narrated, described)
                Kind.FAILED
            } else {
                assertDecided(case, traced, narrated, described)
            }
            kinds.merge(kind, 1, Int::plus)
        }
        // Each kind of case must actually occur for the comparison to mean anything.
        kinds[Kind.SAME].assert().isGreaterThan(ITERATIONS / 2)
        kinds[Kind.FAILED].assert().isGreaterThan(ITERATIONS / 20)
        indeterminate.assert().isPositive()
    }

    /** X6: the failure ends the evaluation, so only what led up to it is shown; the narration goes on. */
    private fun assertFailed(traced: Explanation, narrated: Explanation, described: String) {
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
        // The narration warns in the order its parallel reads answer, the trace in layer order.
        traced.warnings.assert().describedAs(described).containsExactlyInAnyOrderElementsOf(narrated.warnings)
        return if (missing) Kind.MISSING_POLICY else Kind.SAME
    }

    companion object {
        private const val SEED = 20261021
        private const val ITERATIONS = 2_000
    }
}
