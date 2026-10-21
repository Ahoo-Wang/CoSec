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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.authorization.engine.Decisions
import me.ahoo.cosec.authorization.engine.LegacyFacts
import me.ahoo.cosec.authorization.engine.PolicySetLoader
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Decision
import reactor.core.Fuseable
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * [SimpleAuthorization]'s decisions, made by the decision engine ([CoSecEngine], RFC 0002).
 *
 * It takes the same arguments and gives the same decisions, verify contexts and error signals, so the two can be
 * compared request by request; until the legacy implementation is removed, only tests and the parity tooling use it,
 * and the starter never wires it. The flow (RFC 0002 §3.4):
 *
 * 1. the [blacklistChecker] first, then the [rootPrincipalMatcher], as before; a blocked or root request reads no
 *    policies;
 * 2. otherwise the three layers are read in parallel ([PolicySetLoader]); a failed read fails the request only if the
 *    evaluation reaches its layer;
 * 3. the engine evaluates, in place, or on [Schedulers.boundedElastic] when a matcher of the request's policies may
 *    block ([me.ahoo.cosec.policy.BlockingAware]); unlike [SimpleAuthorization], the whole evaluation moves, not one
 *    layer;
 * 4. a permit or deny maps back to the [AuthorizationDecision] [SimpleAuthorization] returns, and a policy statement
 *    or role permission is set as the context's [VerifyContext]. An indeterminate decision is its cause's original
 *    exception as an error signal, so the status codes stay the same.
 *
 * Matchers run in a statement-scoped context: path variables never leave the statement whose action captured them,
 * and the caller's context never sees any.
 */
class EngineAuthorization internal constructor(
    policyRepository: PolicyRepository,
    appRolePermissionRepository: AppRolePermissionRepository,
    private val blacklistChecker: BlacklistChecker,
    private val rootPrincipalMatcher: RootPrincipalMatcher,
    /** Only the benchmarks set it, to measure traced evaluations; the trace-based explanation will read the trace. */
    private val trace: TraceLevel
) : DecisionAuthorization {
    constructor(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
    ) : this(policyRepository, appRolePermissionRepository, blacklistChecker, rootPrincipalMatcher, TraceLevel.OFF)

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val loader = PolicySetLoader(policyRepository, appRolePermissionRepository)

    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> = Mono.defer {
        blacklistChecker
            .check(request, context)
            .flatMap { allowed ->
                when {
                    !allowed -> decide(LegacyFacts.BLOCKED, PolicySet.NOT_LOADED, null, context)
                    rootPrincipalMatcher.isRoot(context.principal) ->
                        decide(LegacyFacts.ROOT, PolicySet.NOT_LOADED, null, context)

                    else -> {
                        val authzRequest = LegacyFacts.authzRequest(request, context)
                        val facts = authzRequest.host as LegacyFacts
                        val policies = loader.load(authzRequest)
                        if (policies is Fuseable.ScalarCallable<*>) {
                            // Read synchronously: no subscription needed.
                            decide(authzRequest, policies.call() as PolicySet, facts, context)
                        } else {
                            policies.flatMap { decide(authzRequest, it, facts, context) }
                        }
                    }
                }
            }
    }

    /** Called inside an operator, so anything the engine or a matcher throws becomes an error signal, as in 7.0. */
    private fun decide(
        request: AuthzRequest,
        policies: PolicySet,
        facts: LegacyFacts?,
        context: SecurityContext
    ): Mono<AuthorizationDecision> {
        if (!PolicySetLoader.requiresBlocking(policies)) {
            return toDecision(CoSecEngine.evaluate(request, policies, trace), policies, facts, context)
        }
        return Mono.fromCallable { CoSecEngine.evaluate(request, policies, trace) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap { toDecision(it, policies, facts, context) }
    }

    private fun toDecision(
        evaluation: Evaluation,
        policies: PolicySet,
        facts: LegacyFacts?,
        context: SecurityContext
    ): Mono<AuthorizationDecision> {
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
}
