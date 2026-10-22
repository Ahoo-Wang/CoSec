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

package me.ahoo.cosec.parity

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.MissingPermissionException
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.explain.ExplainLayer
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.cosec.policy.isPolicyEvaluation
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class DecisionParityTest {
    private val policy = CoSecJsonSerializer.readValue(
        """
        {
          "id": "orders", "name": "Orders", "type": "global", "tenantId": "t",
          "statements": [
            { "name": "read", "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
            { "name": "no-internal", "effect": "deny", "action": "/orders/internal/**" }
          ]
        }
        """.trimIndent(),
        Policy::class.java,
    )

    private val policyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(policy))
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

    private fun create(factory: AuthorizationFactory): DecisionAuthorization =
        factory.create(policyRepository, appRolePermissionRepository, BlacklistChecker.NoOp, RootPrincipalMatcher.NONE)

    private val legacy = create(AuthorizationFactory.LEGACY)
    private val engine = create(AuthorizationFactory.ENGINE)
    private val alice = SimplePrincipal("alice")
    private val read = EvaluateRequest(path = "/orders/1", method = "GET")

    private fun answering(answer: (Request, SecurityContext) -> Mono<AuthorizationDecision>) =
        object : DecisionAuthorization {
            override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
                answer(request, context)
        }

    @Test
    fun equalAnswersAreTheSame() {
        val allowed = DecisionParity(legacy, engine).compare(read, alice)
        allowed.verdict.assert().isEqualTo(ParityVerdict.SAME)
        allowed.bothFailed.assert().isFalse()
        allowed.baseline.tuple.assert().isEqualTo(allowed.candidate.tuple)
        allowed.baseline.tuple.status.assert().isEqualTo(SecurityStatus.ALLOWED)
        allowed.baseline.tuple.decidedBy?.policyId.assert().isEqualTo("orders")
        allowed.baseline.tuple.error.assert().isNull()

        val denied = DecisionParity(legacy, engine).compare(
            EvaluateRequest(path = "/orders/internal/1", method = "GET"),
            SimpleSecurityContext.anonymous(),
        )
        denied.verdict.assert().isEqualTo(ParityVerdict.SAME)
        denied.baseline.tuple.status.assert().isEqualTo(SecurityStatus.UNAUTHORIZED)
        denied.baseline.tuple.reason.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY.reason)
        denied.baseline.tuple.decidedBy?.statementName.assert().isEqualTo("no-internal")
    }

    @Test
    fun eachSideRunsInItsOwnContextMarkedAsPolicyEvaluation() {
        val result = DecisionParity(legacy, engine).compare(read, alice)
        result.baseline.context.assert().isNotSameAs(result.candidate.context)
        result.baseline.context.isPolicyEvaluation.assert().isTrue()
        result.candidate.context.isPolicyEvaluation.assert().isTrue()
        (result.baseline.verifyContext as PolicyVerifyContext).policy.id.assert().isEqualTo("orders")
        (result.candidate.verifyContext as PolicyVerifyContext).policy.id.assert().isEqualTo("orders")
    }

    @Test
    fun anInjectedDifferenceIsAMismatch() {
        // Answers like the engine, but decided by another statement: the deciding rule is part of the tuple.
        val renamed = answering { request, context ->
            engine.decide(request, context).map { decision ->
                val verifyContext = decision.verifyContext as PolicyVerifyContext
                val other = PolicyVerifyContext(
                    verifyContext.source,
                    verifyContext.policy,
                    1,
                    verifyContext.policy.statements[1],
                    verifyContext.result,
                )
                context.setVerifyContext(other)
                decision.copy(verifyContext = other)
            }
        }
        val result = DecisionParity(legacy, renamed).compare(read, alice)
        result.verdict.assert().isEqualTo(ParityVerdict.MISMATCH)
        result.candidate.tuple.decidedBy?.statementIndex.assert().isEqualTo(1)

        val denying = answering { _, _ -> Mono.just(AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY)) }
        val denied = DecisionParity(legacy, denying).compare(read, alice)
        denied.verdict.assert().isEqualTo(ParityVerdict.MISMATCH)
        denied.candidate.tuple.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
    }

    @Test
    fun aCandidateFailingWhereTheBaselineDecidesIsACandidateError() {
        val failing = answering { _, _ -> Mono.error(IllegalStateException("broken")) }
        val result = DecisionParity(legacy, failing).compare(read, alice)
        result.verdict.assert().isEqualTo(ParityVerdict.CANDIDATE_ERROR)
        result.candidate.tuple.status.assert().isEqualTo(SecurityStatus.ERROR)
        result.candidate.tuple.error.assert().isEqualTo(IllegalStateException::class.java.name)
        result.candidate.failure?.message.assert().isEqualTo("broken")

        // The other way round the baseline failed: a difference, not a candidate error.
        DecisionParity(failing, legacy).compare(read, alice).verdict.assert().isEqualTo(ParityVerdict.MISMATCH)
    }

    @Test
    fun failuresAreCompared() {
        val missing = answering { _, _ -> Mono.error(MissingPolicyException(setOf("p"))) }
        val throwing = answering { _, _ -> throw MissingPolicyException(setOf("q")) }
        val same = DecisionParity(missing, throwing).compare(read, alice)
        same.verdict.assert().isEqualTo(ParityVerdict.SAME)
        same.bothFailed.assert().isTrue()

        val other = answering { _, _ -> Mono.error(IllegalStateException("store down")) }
        DecisionParity(missing, other).compare(read, alice).verdict.assert().isEqualTo(ParityVerdict.MISMATCH)
    }

    @Test
    fun statusComesFromThePipelineMapping() {
        val timeout = answering { _, _ -> Mono.error(RegexTimeoutException("timeout")) }
        val result = DecisionParity(timeout, timeout).compare(read, alice)
        result.verdict.assert().isEqualTo(ParityVerdict.SAME)
        // The ReDoS guard is an expected deny, not an error.
        result.baseline.tuple.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
        result.baseline.tuple.reason.assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY.reason)
        result.baseline.tuple.error.assert().isEqualTo(RegexTimeoutException::class.java.name)

        val empty = answering { _, _ -> Mono.empty() }
        val implicit = answering { _, _ -> Mono.just(AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY)) }
        val emptyResult = DecisionParity(empty, implicit).compare(read, alice)
        emptyResult.verdict.assert().isEqualTo(ParityVerdict.SAME)
        emptyResult.baseline.tuple.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
    }

    private fun parity(blocked: Boolean, root: Boolean): DecisionParity {
        val blacklist = object : BlacklistChecker {
            override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(!blocked)
        }
        val rootMatcher = RootPrincipalMatcher { root }
        return DecisionParity(
            AuthorizationFactory.LEGACY.create(policyRepository, appRolePermissionRepository, blacklist, rootMatcher),
            AuthorizationFactory.ENGINE.create(policyRepository, appRolePermissionRepository, blacklist, rootMatcher),
        )
    }

    @Test
    fun theBlacklistWinsOverRootAndOverAMatchingAllow() {
        // Blocked and root: the blacklist decides (S1 before S2).
        val blockedRoot = parity(blocked = true, root = true).compare(read, alice)
        blockedRoot.verdict.assert().isEqualTo(ParityVerdict.SAME)
        blockedRoot.baseline.tuple.decidedBy?.layer.assert().isEqualTo(ExplainLayer.BLACKLIST)
        blockedRoot.baseline.tuple.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)

        // Blocked, with a global ALLOW that matches.
        val blockedAllow = parity(blocked = true, root = false).compare(read, alice)
        blockedAllow.verdict.assert().isEqualTo(ParityVerdict.SAME)
        blockedAllow.baseline.tuple.decidedBy?.layer.assert().isEqualTo(ExplainLayer.BLACKLIST)

        // Root, with a global DENY that matches: root skips every policy (D5).
        val rootDeny = parity(blocked = false, root = true)
            .compare(EvaluateRequest(path = "/orders/internal/1", method = "GET"), alice)
        rootDeny.verdict.assert().isEqualTo(ParityVerdict.SAME)
        rootDeny.baseline.tuple.decidedBy?.layer.assert().isEqualTo(ExplainLayer.ROOT)
        rootDeny.baseline.tuple.status.assert().isEqualTo(SecurityStatus.ALLOWED)
    }

    @Test
    fun anImplementationCheckingRootBeforeTheBlacklistIsAMismatch() {
        val rootFirst = answering { _, _ -> Mono.just(AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext)) }
        val blocked = object : BlacklistChecker {
            override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(false)
        }
        val baseline = AuthorizationFactory.LEGACY.create(
            policyRepository,
            appRolePermissionRepository,
            blocked,
            RootPrincipalMatcher { true },
        )
        DecisionParity(baseline, rootFirst).compare(read, alice).verdict.assert().isEqualTo(ParityVerdict.MISMATCH)
    }

    @Test
    fun aDifferenceInTheReasonAloneIsAMismatch() {
        // An explicit deny that reports no rule against an implicit deny: same status, no rule on either side.
        val explicit = answering { _, _ -> Mono.just(AuthorizationDecision(AuthorizeResult.EXPLICIT_DENY)) }
        val implicit = answering { _, _ -> Mono.just(AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY)) }
        val result = DecisionParity(implicit, explicit).compare(read, alice)
        result.verdict.assert().isEqualTo(ParityVerdict.MISMATCH)
        result.baseline.tuple.status.assert().isEqualTo(result.candidate.tuple.status)
        result.baseline.tuple.decidedBy.assert().isNull()
        result.candidate.tuple.decidedBy.assert().isNull()
    }

    @Test
    fun failuresOfOneClassForDifferentCausesAreFlagged() {
        val missingP = answering { _, _ -> Mono.error(MissingPolicyException(linkedSetOf("p", "q"))) }
        val missingPReordered = answering { _, _ -> Mono.error(MissingPolicyException(linkedSetOf("q", "p"))) }
        val missingR = answering { _, _ -> Mono.error(MissingPolicyException(setOf("r"))) }
        val reordered = DecisionParity(missingP, missingPReordered).compare(read, alice)
        reordered.bothFailed.assert().isTrue()
        reordered.failedAlikeForDifferentCauses.assert().isFalse()
        reordered.baseline.failureCause.assert().isEqualTo("PRINCIPAL policies [p, q]")

        val different = DecisionParity(missingP, missingR).compare(read, alice)
        different.verdict.assert().isEqualTo(ParityVerdict.SAME)
        different.failedAlikeForDifferentCauses.assert().isTrue()

        val permission = answering { _, _ -> Mono.error(MissingPermissionException("app", "role", setOf("b", "a"))) }
        DecisionParity(permission, permission).compare(read, alice).baseline.failureCause.assert()
            .isEqualTo("app [app] role [role] permissions [a, b]")
        DecisionParity(legacy, engine).compare(read, alice).baseline.failureCause.assert().isNull()
    }

    @Test
    fun sidesPrintWhatTheyAnswered() {
        DecisionParity(legacy, engine).compare(read, alice).baseline.toString().assert().contains("ALLOWED")
    }
}
