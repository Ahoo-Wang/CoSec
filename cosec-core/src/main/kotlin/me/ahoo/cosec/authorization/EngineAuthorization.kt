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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.engine.EngineDecisionFlow
import me.ahoo.cosec.authorization.engine.EvaluationObserver
import me.ahoo.cosec.authorization.engine.PolicySetLoader
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.TraceLevel
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
    blacklistChecker: BlacklistChecker,
    rootPrincipalMatcher: RootPrincipalMatcher,
    /** Set by the benchmarks, to measure traced evaluations, and by the trace-based explanation, which reads the trace. */
    trace: TraceLevel,
    /** Sees every evaluation, with the policy set it read, before its decision is mapped. */
    observer: EvaluationObserver? = null
) : DecisionAuthorization {
    constructor(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
    ) : this(policyRepository, appRolePermissionRepository, blacklistChecker, rootPrincipalMatcher, TraceLevel.OFF)

    private val loader = PolicySetLoader(policyRepository, appRolePermissionRepository)
    private val flow = EngineDecisionFlow(blacklistChecker, rootPrincipalMatcher, trace, observer)
    private val readPolicies = EngineDecisionFlow.PolicyStep { request, facts, context ->
        val policies = loader.load(request)
        if (policies is Fuseable.ScalarCallable<*>) {
            // Read synchronously: no subscription needed.
            flow.evaluate(request, policies.call() as PolicySet, facts, context)
        } else {
            policies.flatMap { flow.evaluate(request, it, facts, context) }
        }
    }

    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
        flow.decide(request, context, readPolicies)
}
