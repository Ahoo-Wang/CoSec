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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** What the control API says about a decision: a request simulated (with a change applied) and a request explained, redacted to what the caller may read. */
internal class DecisionQueries(
    private val changes: Changes,
    private val authority: ChangeAuthority,
    private val simulator: Simulator,
    private val retained: RetainedDocuments,
) {
    suspend fun simulate(operator: Operator, request: SimulatedRequest, candidate: ChangeId?): Simulation {
        val decidable = decidable(operator, request, ControlActions.SIMULATE)
        val tenant = decidable.tenant
        val simulated = decidable.request
        val scope = resolved(simulated) ?: return rejected(simulated)
        // The tenant's document first: with the scope, it decides whether the app's is read, as enforcement decides.
        val tenantHead = retained.held(tenant)
        val keys = simulator.keys(scope, tenantHead?.document)
        val heads = keys.associateWith { if (it == tenant) tenantHead else retained.held(it) }
        val enforced = simulator.decide(simulated, scope, documents(heads))
        val proposed = candidate?.let { id ->
            val change = changes.visible(operator, id)
            authority.require(operator, change.key, ControlActions.SIMULATE)
            // What the view read, not what [keys] fetched: an app the tenant binds is not read when it does not compile.
            if (change.key !in enforced.read) {
                throw ChangeRejectedException(
                    BAD_REQUEST,
                    "change ${id.value} is of [${change.key}], which this request does not read"
                )
            }
            val document = changes.candidateOf(id) ?: throw ChangeRejectedException(CONFLICT, "change ${id.value} no longer applies")
            // A tenant change may make the request read another app document, or none (RC1-17): as once it publishes.
            val fetched = if (change.key == tenant) simulator.keys(scope, document) else keys
            // The candidate is no stored content: compiled once, here; the heads come from the cache.
            val proposedHeads = fetched.associateWith { heads[it] ?: retained.held(it) } + (change.key to Held(document, null))
            simulator.decide(simulated, scope, documents(proposedHeads))
        }
        val redaction = redaction(operator, (enforced.read + proposed?.read.orEmpty()).distinct())
        return Simulation(
            redaction.decision(enforced.evaluation.decision),
            redaction.trace(enforced.evaluation),
            proposed?.let { redaction.decision(it.evaluation.decision) },
            proposed?.let { redaction.trace(it.evaluation) },
            proposed != null && differs(enforced.evaluation.decision, proposed.evaluation.decision),
        )
    }

    suspend fun explain(operator: Operator, request: SimulatedRequest, at: ViewVersion?): Explanation {
        val decidable = decidable(operator, request, ControlActions.EXPLAIN)
        val tenant = decidable.tenant
        val explained = decidable.request
        val scope = resolved(explained)
        if (scope == null) {
            val rejected = rejected(explained)
            return Explanation(at ?: ViewVersion(0, null, 0), rejected.enforced, null)
        }
        // The tenant's document at the revision decided on first: it decides, by enforcement's rule, whether the app's
        // was read (RC1-17), so the app revision an audit event recorded is the one explained.
        suspend fun documentAt(key: SnapshotKey): Held? = if (at == null) {
            retained.held(key)
        } else {
            val revision = when (key) {
                SnapshotKey.Platform -> at.platform
                is SnapshotKey.App -> at.app
                else -> at.tenant
            }
            revision?.takeIf { it > 0 }?.let { retained.retainedHeld(key, it) }
        }
        val tenantDocument = documentAt(tenant)
        val keys = simulator.keys(scope, tenantDocument?.document)
        val documents = keys.associateWith { if (it == tenant) tenantDocument else documentAt(it) }
        // The revisions the view read (R2-5): an app's only when it took the app's document.
        val decided = simulator.decide(explained, scope, documents(documents))
        val redaction = redaction(operator, decided.read)
        return Explanation(
            decided.version,
            redaction.decision(decided.evaluation.decision),
            redaction.trace(decided.evaluation),
        )
    }

    /** A simulated or explained request as enforcement would decide it, and the tenant whose document decides it. */
    private class Decidable(val tenant: SnapshotKey.Tenant, val request: SimulatedRequest)

    /**
     * [request] as enforcement would decide it, once [operator] may [action] on the tenant whose document decides it
     * (RC1-40): the one the scope resolves to, never one the request only names. A request without a principal is
     * decided on tenant `(0)`, so naming another tenant is a 400, after the authorization, as enforcement would decide
     * it on `(0)`. Its resource is the one enforcement would decide on ([EnforceableOrRefused], RC1-41).
     */
    private suspend fun decidable(operator: Operator, request: SimulatedRequest, action: Action): Decidable {
        val tenant = simulator.tenantOf(request)
        val key = SnapshotKey.Tenant(tenant.value)
        authority.require(operator, key, action)
        if (tenant != request.tenant) {
            throw ChangeRejectedException(
                BAD_REQUEST,
                "A request without a principal is decided on tenant ${tenant.value}, as enforcement decides it: " +
                    "it names no other tenant."
            )
        }
        return Decidable(
            key,
            request.copy(
                action = EnforceableOrRefused.action(request.action),
                resource = EnforceableOrRefused.resource(request.resource)
            )
        )
    }

    private fun resolved(request: SimulatedRequest) = (simulator.scope(request) as? ScopeResolution.Resolved)?.scope

    private fun rejected(request: SimulatedRequest): Simulation {
        val reason = (simulator.scope(request) as ScopeResolution.Rejected).reason
        val view = DecisionView("Rejected", null, reason.name, redacted = false)
        return Simulation(view, null, null, null, differs = false)
    }

    /** The document kinds of [keys] the caller may read; the simulated tenant's needs its own check too. */
    private suspend fun redaction(operator: Operator, keys: List<SnapshotKey>): Redaction = Redaction(
        keys.filter { authority.holds(operator, it, ControlActions.READ) }.map { kindOf(it) }.toSet(),
    )

    private fun documents(heads: Map<SnapshotKey, Held?>) = HeldDocuments(
        heads[SnapshotKey.Platform],
        heads.entries.firstOrNull { it.key is SnapshotKey.App }?.value,
        heads.entries.firstOrNull { it.key is SnapshotKey.Tenant }?.value,
    )

    private fun kindOf(key: SnapshotKey): DocumentKind = when (key) {
        SnapshotKey.Platform -> DocumentKind.PLATFORM
        is SnapshotKey.App -> DocumentKind.APP
        else -> DocumentKind.TENANT
    }

    private companion object {
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
    }
}
