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
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.EngineAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.getVerifyContext
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.tenant
import me.ahoo.cosec.explain.DecisionRef
import me.ahoo.cosec.explain.toDecisionRef
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.markPolicyEvaluation
import reactor.core.publisher.Mono

/**
 * What one authorization answered, as compared by RFC 0002 §8.3. Two answers are the same only when every field is.
 *
 * @param status the status [SecurityPipeline] maps the answer to (derived through its own mapping, never copied)
 * @param reason [me.ahoo.cosec.api.authorization.AuthorizeResult.reason] of that mapping: tells an explicit deny from
 * an implicit one
 * @param decidedBy the rule that decided, null for an implicit deny or an error
 * @param error the class of the exception the authorization failed with, null when it decided
 */
data class DecisionTuple(
    val status: SecurityStatus,
    val reason: String,
    val decidedBy: DecisionRef?,
    val error: String?
)

enum class ParityVerdict {
    /** Both tuples are equal, including both failing with the same exception class. */
    SAME,

    /** The tuples differ, and it is not only the candidate failing. */
    MISMATCH,

    /** The candidate failed where the baseline decided. */
    CANDIDATE_ERROR,
}

/**
 * One side of a comparison.
 *
 * @param context the fresh context this side ran with, to inspect what it wrote (e.g. its [VerifyContext])
 * @param failure what the authorization failed with, null when it decided
 */
class ParitySide(val tuple: DecisionTuple, val context: SecurityContext, val failure: Throwable?) {
    val verifyContext: VerifyContext?
        get() = context.getVerifyContext()
}

class ParityResult(val verdict: ParityVerdict, val baseline: ParitySide, val candidate: ParitySide) {
    /** Both sides failed with the same exception class: [ParityVerdict.SAME], counted apart by the replay. */
    val bothFailed: Boolean
        get() = baseline.failure != null && candidate.failure != null && verdict == ParityVerdict.SAME
}

/**
 * Compares two [DecisionAuthorization]s on the same request (RFC 0002 §8.1): the [baseline] that enforces today and a
 * [candidate] meant to replace it. Used by the parity tests, `PolicyTestKit.parity` and the offline replay
 * ([DecisionReplay]); RFC 0003 and RFC 0004 reuse it for their own gates.
 *
 * - Only the pure decision is compared: wrap neither side in rate limiting, auditing or shadow decorators.
 * - Unlike [me.ahoo.cosec.shadow.ShadowAuthorization], failures are compared too: equivalence on the error path is part
 *   of the gate.
 * - Each side runs with its own fresh [SimpleSecurityContext] marked as a policy evaluation, so neither sees what the
 *   other wrote. A custom matcher that behaves differently under
 *   [me.ahoo.cosec.policy.isPolicyEvaluation] may therefore differ from production; such a difference is not a defect of
 *   either side.
 *
 * Blocks until both sides answer: call it from a test or a tool, never from an event loop.
 */
class DecisionParity(val baseline: DecisionAuthorization, val candidate: DecisionAuthorization) {
    fun compare(request: Request, principal: CoSecPrincipal, tenant: Tenant = principal.tenant): ParityResult {
        val baselineSide = evaluate(baseline, request, principal, tenant)
        val candidateSide = evaluate(candidate, request, principal, tenant)
        val verdict = when {
            baselineSide.tuple == candidateSide.tuple -> ParityVerdict.SAME
            candidateSide.failure != null && baselineSide.failure == null -> ParityVerdict.CANDIDATE_ERROR
            else -> ParityVerdict.MISMATCH
        }
        return ParityResult(verdict, baselineSide, candidateSide)
    }

    fun compare(request: Request, context: SecurityContext): ParityResult =
        compare(request, context.principal, context.tenant)

    private fun evaluate(
        authorization: DecisionAuthorization,
        request: Request,
        principal: CoSecPrincipal,
        tenant: Tenant
    ): ParitySide {
        val context = SimpleSecurityContext(principal, tenant).markPolicyEvaluation()
        // defer: an authorization that throws synchronously fails like one that signals an error, as in the pipeline.
        return Mono.defer { authorization.decide(request, context) }
            // An empty answer is an implicit deny, as in the pipeline.
            .defaultIfEmpty(AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY))
            .map { decision ->
                val mapped = SecurityPipeline.toDecision(request, context, decision)
                ParitySide(
                    DecisionTuple(mapped.status, mapped.result.reason, decision.verifyContext?.toDecisionRef(), null),
                    context,
                    null,
                )
            }
            .onErrorResume { failure ->
                val mapped = SecurityPipeline.toErrorDecision(request, context, failure)
                Mono.just(
                    ParitySide(
                        DecisionTuple(mapped.status, mapped.result.reason, null, failure.javaClass.name),
                        context,
                        failure,
                    ),
                )
            }
            .block()!!
    }
}

/**
 * Builds one side of a comparison from the repositories and checks both sides share.
 */
fun interface AuthorizationFactory {
    fun create(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker,
        rootPrincipalMatcher: RootPrincipalMatcher
    ): DecisionAuthorization

    companion object {
        /** The 7.0 implementation, which enforces until RFC 0002 PR-6. */
        @JvmField
        val LEGACY: AuthorizationFactory = AuthorizationFactory(::SimpleAuthorization)

        /** The implementation backed by the decision engine (RFC 0002). */
        @JvmField
        val ENGINE: AuthorizationFactory = AuthorizationFactory(::EngineAuthorization)
    }
}
