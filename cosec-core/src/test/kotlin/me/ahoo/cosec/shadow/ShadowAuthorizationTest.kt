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
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.explain.ExplainLayer
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.isPolicyEvaluation
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class ShadowAuthorizationTest {
    private val enforcedGlobal = mutableListOf<Policy>()
    private val diffs = CopyOnWriteArrayList<ShadowDiff>()
    private val context = SimpleSecurityContext(SimplePrincipal("user"))

    private val policyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.fromCallable { enforcedGlobal.toList() }
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
        override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
    }

    private val appRolePermissionRepository = object : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: String,
            spaceId: String,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> = Mono.empty()
    }

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun policy(
        id: String,
        effect: Effect,
        pattern: String,
        condition: ConditionMatcher = AllConditionMatcher.INSTANCE
    ) = PolicyData(
        id,
        "",
        id,
        "",
        PolicyType.GLOBAL,
        "tenant",
        condition,
        listOf(StatementData(name = pattern, effect = effect, action = path(pattern))),
    )

    private fun shadow(
        candidates: List<Policy>,
        sink: ShadowEventSink = ShadowEventSink(diffs::add),
        sampleRate: Double = 1.0,
        reportRuleChanges: Boolean = false,
        random: Random = Random.Default
    ) = ShadowAuthorization(
        delegate = SimpleAuthorization(policyRepository, appRolePermissionRepository),
        policyRepository = policyRepository,
        appRolePermissionRepository = appRolePermissionRepository,
        policyProvider = ShadowPolicyProvider { candidates },
        sink = sink,
        sampleRate = sampleRate,
        reportRuleChanges = reportRuleChanges,
        random = random,
    )

    private fun ShadowAuthorization.authorize(path: String): AuthorizeResult? =
        authorize(EvaluateRequest(path = path), context).block()

    @Test
    fun widened() {
        val authorization = shadow(listOf(policy("orders", Effect.ALLOW, "/orders/**")))
        // The enforced decision is returned unchanged.
        authorization.authorize("/orders/1").assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        val diff = diffs.single()
        diff.kind.assert().isEqualTo(ShadowDiffKind.WIDENED)
        diff.path.assert().isEqualTo("/orders/1")
        diff.principalId.assert().isEqualTo("user")
        diff.enforced.authorized.assert().isFalse()
        diff.candidate.decidedBy?.layer.assert().isEqualTo(ExplainLayer.GLOBAL_POLICY)
        diff.candidate.decidedBy?.policyId.assert().isEqualTo("orders")
    }

    @Test
    fun rootIsOffByDefault() {
        val root = SimpleSecurityContext(
            SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.PLATFORM),
        )
        val authorization = ShadowAuthorization(
            delegate = SimpleAuthorization(
                policyRepository,
                appRolePermissionRepository,
                rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT,
            ),
            policyRepository = policyRepository,
            appRolePermissionRepository = appRolePermissionRepository,
            policyProvider = ShadowPolicyProvider { listOf(policy("other", Effect.ALLOW, "/other/**")) },
            sink = ShadowEventSink(diffs::add),
        )
        authorization.authorize(EvaluateRequest(path = "/orders/1"), root).block()
            .assert().isEqualTo(AuthorizeResult.ALLOW)
        diffs.single().kind.assert().isEqualTo(ShadowDiffKind.NARROWED)
    }

    @Test
    fun narrowedByReplacingPolicy() {
        enforcedGlobal += policy("orders", Effect.ALLOW, "/orders/**")
        val authorization = shadow(listOf(policy("orders", Effect.ALLOW, "/orders/public/**")))
        authorization.authorize("/orders/1").assert().isEqualTo(AuthorizeResult.ALLOW)
        diffs.single().kind.assert().isEqualTo(ShadowDiffKind.NARROWED)
        diffs.single().candidate.decidedBy.assert().isNull()
    }

    @Test
    fun sameResultReportsNothing() {
        enforcedGlobal += policy("orders", Effect.ALLOW, "/orders/**")
        shadow(listOf(policy("orders-v2", Effect.ALLOW, "/orders/**"))).authorize("/orders/1")
        diffs.assert().isEmpty()
    }

    @Test
    fun ruleChangesOnRequest() {
        enforcedGlobal += policy("orders", Effect.ALLOW, "/orders/**")
        // The candidate replaces "orders" so that "orders-v2" decides instead, with the same result.
        val candidates =
            listOf(policy("orders", Effect.ALLOW, "/none/**"), policy("orders-v2", Effect.ALLOW, "/orders/**"))
        shadow(candidates, reportRuleChanges = true).authorize("/orders/1")
        val diff = diffs.single()
        diff.kind.assert().isEqualTo(ShadowDiffKind.RULE_CHANGED)
        diff.enforced.decidedBy?.policyId.assert().isEqualTo("orders")
        diff.candidate.decidedBy?.policyId.assert().isEqualTo("orders-v2")
    }

    @Test
    fun candidateErrorNeverAffectsDecision() {
        enforcedGlobal += policy("orders", Effect.ALLOW, "/orders/**")
        val failing =
            policy("orders", Effect.ALLOW, "/orders/**", RecordingCondition(error = IllegalStateException("boom")))
        shadow(listOf(failing)).authorize("/orders/1").assert().isEqualTo(AuthorizeResult.ALLOW)
        val diff = diffs.single()
        diff.kind.assert().isEqualTo(ShadowDiffKind.CANDIDATE_ERROR)
        diff.candidate.error.assert().contains("boom")
    }

    @Test
    fun sinkFailureNeverAffectsDecision() {
        val authorization = shadow(
            listOf(policy("orders", Effect.ALLOW, "/orders/**")),
            sink = ShadowEventSink { error("sink down") },
        )
        authorization.authorize("/orders/1").assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun enforcedErrorPropagatesWithoutShadow() {
        enforcedGlobal += policy("orders", Effect.ALLOW, "/orders/**", RecordingCondition(error = IllegalStateException("boom")))
        shadow(listOf(policy("orders-v2", Effect.ALLOW, "/orders/**")))
            .authorize(EvaluateRequest(path = "/orders/1"), context)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()
        diffs.assert().isEmpty()
    }

    @Test
    fun runsOnIsolatedPolicyEvaluationContext() {
        val recording = RecordingCondition()
        shadow(listOf(policy("orders", Effect.ALLOW, "/orders/**", recording))).authorize("/orders/1")
        recording.contexts.single().apply {
            isPolicyEvaluation.assert().isTrue()
            assert().isNotSameAs(context)
        }
        context.isPolicyEvaluation.assert().isFalse()
    }

    @Test
    fun sampling() {
        val candidates = listOf(policy("orders", Effect.ALLOW, "/orders/**"))
        shadow(candidates, sampleRate = 0.0).authorize("/orders/1")
        diffs.assert().isEmpty()
        shadow(candidates, sampleRate = 0.5, random = Random(1)).apply {
            repeat(100) { authorize("/orders/1") }
        }
        diffs.size.assert().isBetween(30, 70)
    }

    @Test
    fun noCandidatesSkipsShadow() {
        shadow(emptyList()).authorize("/orders/1")
        diffs.assert().isEmpty()
    }

    @Test
    fun reusesCandidateAuthorizationForSameList() {
        val recording = RecordingCondition()
        val candidates = listOf(policy("orders", Effect.ALLOW, "/orders/**", recording))
        val authorization = shadow(candidates)
        repeat(3) { authorization.authorize("/orders/1") }
        recording.contexts.size.assert().isEqualTo(3)
        diffs.size.assert().isEqualTo(3)
    }

    @Test
    fun rejectsInvalidSampleRate() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            shadow(emptyList(), sampleRate = 1.5)
        }
    }

    private class RecordingCondition(private val error: RuntimeException? = null) : ConditionMatcher {
        val contexts = CopyOnWriteArrayList<SecurityContext>()
        override val type: String = "recording"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()

        override fun match(request: Request, securityContext: SecurityContext): Boolean {
            contexts += securityContext
            error?.let { throw it }
            return true
        }
    }
}
