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
import me.ahoo.cosec.authorization.engine.CompiledApp
import me.ahoo.cosec.authorization.engine.CompiledPolicies
import me.ahoo.cosec.authorization.engine.EngineDecisionFlow
import me.ahoo.cosec.authorization.engine.EvaluationObserver
import me.ahoo.cosec.authorization.engine.LayerReads
import me.ahoo.cosec.authorization.engine.LegacyFacts
import me.ahoo.cosec.authorization.engine.PolicySnapshot
import me.ahoo.cosec.authorization.engine.SnapshotPolicySetLoader
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.snapshot.SnapshotSource
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * [EngineAuthorization]'s decisions over a policy snapshot (RFC 0003 §5.1): the layers come from [snapshotSource]
 * through [SnapshotPolicySetLoader] instead of the repositories; the rest is [EngineAuthorization]'s own flow
 * ([EngineDecisionFlow]).
 *
 * Test assembly only. It ships in the jar but nothing wires it: the equivalence tests and the benchmarks use it, until
 * RFC 0003 PR-5 makes this the flow of [SimpleAuthorization] and this class goes away. A snapshot that is installed
 * is evaluated on the caller's thread without any future; a cold load continues on [Schedulers.boundedElastic]; an
 * evaluation reaching a layer whose document failed fails closed, as a failed repository read does.
 */
internal class SnapshotEngineAuthorization(
    private val snapshotSource: SnapshotSource<CompiledPolicies, CompiledApp>,
    blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
    rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE,
    trace: TraceLevel = TraceLevel.OFF,
    /** Tests only: sees each evaluation with the snapshot it read. */
    private val onEvaluated: ((PolicySnapshot, Evaluation) -> Unit)? = null,
) : DecisionAuthorization {
    private val flow = EngineDecisionFlow(blacklistChecker, rootPrincipalMatcher, trace, null)
    private val readSnapshot = EngineDecisionFlow.PolicyStep { request, facts, context ->
        val reads = LayerReads.of(facts)
        val snapshot = snapshotSource.peek(reads.appDocument)
        if (snapshot != null) {
            evaluateOn(request, facts, reads, snapshot, context)
        } else {
            Mono.fromFuture { snapshotSource.load(reads.appDocument) }
                .publishOn(Schedulers.boundedElastic())
                .flatMap { evaluateOn(request, facts, reads, it, context) }
        }
    }

    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
        flow.decide(request, context, readSnapshot)

    private fun evaluateOn(
        request: AuthzRequest,
        facts: LegacyFacts,
        reads: LayerReads,
        snapshot: PolicySnapshot,
        context: SecurityContext,
    ): Mono<AuthorizationDecision> {
        snapshotSource.checkFresh(snapshot, context.tenant.tenantId)
        val observer = onEvaluated?.let { EvaluationObserver { evaluation, _ -> it(snapshot, evaluation) } }
        return flow.evaluate(request, SnapshotPolicySetLoader.load(reads, snapshot), facts, context, observer)
    }
}
