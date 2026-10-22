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

package me.ahoo.cosec.authorization.engine

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.EngineAuthorization
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Decision
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * The decision flow of RFC 0002 §3.4, whatever the policies are read from: the blacklist, then root, then the
 * request's policies through a [PolicyStep], evaluated in place or on [Schedulers.boundedElastic], and the engine's
 * decision mapped back to 7.0's. The one copy of it, for [EngineAuthorization] (repositories) and
 * [me.ahoo.cosec.authorization.SnapshotEngineAuthorization] (a policy snapshot).
 */
internal class EngineDecisionFlow(
    private val blacklistChecker: BlacklistChecker,
    private val rootPrincipalMatcher: RootPrincipalMatcher,
    private val trace: TraceLevel,
    private val observer: EvaluationObserver?,
) {
    /** Reads a request's policies and decides it with [EngineDecisionFlow.evaluate]. */
    fun interface PolicyStep {
        fun decide(request: AuthzRequest, facts: LegacyFacts, context: SecurityContext): Mono<AuthorizationDecision>
    }

    fun decide(request: Request, context: SecurityContext, policies: PolicyStep): Mono<AuthorizationDecision> =
        Mono.defer {
            blacklistChecker
                .check(request, context)
                .flatMap { allowed ->
                    when {
                        !allowed -> evaluate(LegacyFacts.BLOCKED, PolicySet.NOT_LOADED, null, context)
                        rootPrincipalMatcher.isRoot(context.principal) ->
                            evaluate(LegacyFacts.ROOT, PolicySet.NOT_LOADED, null, context)

                        else -> {
                            val authzRequest = LegacyFacts.authzRequest(request, context)
                            policies.decide(authzRequest, authzRequest.host as LegacyFacts, context)
                        }
                    }
                }
        }

    /**
     * Evaluates [policies], in place unless a matcher may block, and maps the decision. Called inside an operator, so
     * anything the engine or a matcher throws becomes an error signal, as in 7.0. [observer] sees the evaluation.
     */
    fun evaluate(
        request: AuthzRequest,
        policies: PolicySet,
        facts: LegacyFacts?,
        context: SecurityContext,
        observer: EvaluationObserver? = this.observer,
    ): Mono<AuthorizationDecision> {
        if (!PolicySetLoader.requiresBlocking(policies)) {
            return toDecision(CoSecEngine.evaluate(request, policies, trace), policies, facts, context, observer)
        }
        return Mono.fromCallable { CoSecEngine.evaluate(request, policies, trace) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap { toDecision(it, policies, facts, context, observer) }
    }

    private fun toDecision(
        evaluation: Evaluation,
        policies: PolicySet,
        facts: LegacyFacts?,
        context: SecurityContext,
        observer: EvaluationObserver?,
    ): Mono<AuthorizationDecision> {
        observer?.evaluated(evaluation, policies)
        val decision = evaluation.decision
        if (decision is Decision.Indeterminate) {
            if (log.isDebugEnabled()) {
                log.debug(
                    decision.cause.error
                ) { "Verify [${facts?.request}] [$context] failed - [${decision.cause}]." }
            }
            return Mono.error(decision.cause.error)
        }
        val authorizationDecision = Decisions.of(decision, policies, facts)
        authorizationDecision.verifyContext.let {
            if (it is PolicyVerifyContext || it is RoleVerifyContext) {
                context.setVerifyContext(it)
            }
        }
        if (log.isDebugEnabled()) {
            log.debug { "Verify [${facts?.request}] [$context] decided by [$decision] - [${authorizationDecision.result}]." }
        }
        return Mono.just(authorizationDecision)
    }

    private companion object {
        /** Logs under [EngineAuthorization], as before the flow was shared. */
        val log = KotlinLogging.logger(EngineAuthorization::class.java.name)
    }
}
