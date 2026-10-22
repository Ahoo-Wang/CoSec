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
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.authorization.engine.CompiledApp
import me.ahoo.cosec.authorization.engine.CompiledPolicies
import me.ahoo.cosec.authorization.engine.Decisions
import me.ahoo.cosec.authorization.engine.LegacyFacts
import me.ahoo.cosec.authorization.engine.PolicySetLoader
import me.ahoo.cosec.authorization.engine.PolicySnapshot
import me.ahoo.cosec.authorization.engine.SnapshotPolicySetLoader
import me.ahoo.cosec.authorization.engine.SnapshotReads
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.snapshot.SnapshotSource
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * [EngineAuthorization]'s decisions over a policy snapshot (RFC 0003 §5.1): the layers come from [snapshotSource]
 * through [SnapshotPolicySetLoader] instead of the repositories.
 *
 * Test assembly only, until RFC 0003 PR-5 makes this the flow of [SimpleAuthorization]: the equivalence tests and the
 * benchmarks use it, and nothing wires it. A snapshot that is installed is evaluated on the caller's thread without
 * any future; a cold load continues on [Schedulers.boundedElastic]; an evaluation reaching a layer whose document
 * failed fails closed, as a failed repository read does. The decision mapping is [EngineAuthorization]'s.
 */
internal class SnapshotEngineAuthorization(
    private val snapshotSource: SnapshotSource<CompiledPolicies, CompiledApp>,
    private val blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
    private val rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE,
    private val trace: TraceLevel = TraceLevel.OFF,
    /** Tests only: sees each evaluation with the snapshot it read. */
    private val onEvaluated: ((PolicySnapshot, Evaluation) -> Unit)? = null,
) : DecisionAuthorization {

    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> = Mono.defer {
        blacklistChecker
            .check(request, context)
            .flatMap { allowed ->
                when {
                    !allowed -> decide(LegacyFacts.BLOCKED, PolicySet.NOT_LOADED, null, null, context)
                    rootPrincipalMatcher.isRoot(context.principal) ->
                        decide(LegacyFacts.ROOT, PolicySet.NOT_LOADED, null, null, context)

                    else -> {
                        val authzRequest = LegacyFacts.authzRequest(request, context)
                        val facts = authzRequest.host as LegacyFacts
                        val reads = SnapshotReads.of(facts)
                        val snapshot = snapshotSource.peek(reads.appId)
                        if (snapshot != null) {
                            evaluateOn(authzRequest, facts, reads, snapshot, context)
                        } else {
                            Mono.fromFuture { snapshotSource.load(reads.appId) }
                                .publishOn(Schedulers.boundedElastic())
                                .flatMap { evaluateOn(authzRequest, facts, reads, it, context) }
                        }
                    }
                }
            }
    }

    private fun evaluateOn(
        request: AuthzRequest,
        facts: LegacyFacts,
        reads: SnapshotReads,
        snapshot: PolicySnapshot,
        context: SecurityContext,
    ): Mono<AuthorizationDecision> {
        snapshotSource.checkFresh(snapshot, context.tenant.tenantId)
        return decide(request, SnapshotPolicySetLoader.load(reads, snapshot), facts, snapshot, context)
    }

    /** Called inside an operator, so anything the engine or a matcher throws becomes an error signal, as in 7.0. */
    private fun decide(
        request: AuthzRequest,
        policies: PolicySet,
        facts: LegacyFacts?,
        snapshot: PolicySnapshot?,
        context: SecurityContext,
    ): Mono<AuthorizationDecision> {
        if (!PolicySetLoader.requiresBlocking(policies)) {
            return toDecision(evaluate(request, policies, snapshot), policies, facts, context)
        }
        return Mono.fromCallable { evaluate(request, policies, snapshot) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap { toDecision(it, policies, facts, context) }
    }

    private fun evaluate(request: AuthzRequest, policies: PolicySet, snapshot: PolicySnapshot?): Evaluation {
        val evaluation = CoSecEngine.evaluate(request, policies, trace)
        if (snapshot != null) {
            onEvaluated?.invoke(snapshot, evaluation)
        }
        return evaluation
    }

    private fun toDecision(
        evaluation: Evaluation,
        policies: PolicySet,
        facts: LegacyFacts?,
        context: SecurityContext,
    ): Mono<AuthorizationDecision> {
        val decision = evaluation.decision
        if (decision is Decision.Indeterminate) {
            return Mono.error(decision.cause.error)
        }
        val authorizationDecision = Decisions.of(decision, policies, facts)
        authorizationDecision.verifyContext.let {
            if (it is PolicyVerifyContext || it is RoleVerifyContext) {
                context.setVerifyContext(it)
            }
        }
        return Mono.just(authorizationDecision)
    }
}
