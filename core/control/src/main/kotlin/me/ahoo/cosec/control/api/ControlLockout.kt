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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.lifecycle.WorkBudget
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock

/**
 * The lock-out check (UC-6, [LockoutCheck]) with the data plane's own decision: the [Simulator] compiles the candidate
 * with the data plane's compiler and decides with `Engine.evaluate`, the candidate in place of its document and the other
 * documents as stored. It covers the documents that gate the control plane for more than their own tenant: the
 * platform's, the platform tenant's (`(platform)`, whose principals hold `cosec:platform-admin`) and [controlApp]'s.
 *
 * Two decisions, both of which must permit:
 * 1. the request in progress (the proposal, or the publish), as the pipeline decided it: the same principal, memberships,
 *    scope, action and resource, headers and request attributes, at this time;
 * 2. `cosec:publish` on the document, asked by the same principal in the default space of [controlApp], as
 *    [PipelineControlAuthorizer] asks it.
 *
 * Its limits, stated where an author reads them (`testing.md`): only the operator's own way back is checked, with the
 * facts of the request in progress. Other operators may be locked out; what a later change, an expiring binding or
 * token, a membership provider or a `time` window does is not seen; external attributes are not fetched, so a statement
 * that reads one decides as its `onMissing` says; a tenant's own document is not checked (the platform's operators can
 * repair it). An operator without a permitted request (none reaches the control plane) is not checked.
 */
@InternalCoSecApi
class ControlLockout(
    revisions: Revisions,
    private val simulator: Simulator = Simulator(),
    private val controlApp: AppId = PipelineControlAuthorizer.CONTROL_APP,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * The host's work budget (`CTL-85`): the check runs within a call that holds its body's weight, so it never waits. A
     * host passes its one budget, the plane's ([ControlPlane.work]); the default is for a check of its own, in a test.
     */
    val work: WorkBudget = ControlDerived.workBudget(),
) : LockoutCheck {
    private val retained = RetainedDocuments(revisions, simulator.cache, work)

    override suspend fun check(operator: Operator, key: SnapshotKey, candidate: PolicyDocument): LockoutCheck.Lockout? {
        if (!covers(key)) {
            return null
        }
        val security = operator.security ?: return null
        val now = clock.millis()
        val request = security.engineRequest(now)
            ?: return LockoutCheck.Lockout("the request in progress could not be decided again")
        refusal(request, key, candidate, "the request in progress (${name(request.request.action)})")?.let { return it }
        val publish = security.engineRequest(now, controlApp, ControlActions.PUBLISH, ControlResources.document(key))
            ?: return LockoutCheck.Lockout("${name(ControlActions.PUBLISH)} is asked in app ${controlApp.value}, which the credential does not declare")
        return refusal(publish, key, candidate, "${name(ControlActions.PUBLISH)} on [$key]")
    }

    private fun name(action: Action): String = "${action.namespace}:${action.verb}"

    private fun covers(key: SnapshotKey): Boolean = key == SnapshotKey.Platform ||
        key == SnapshotKey.Tenant(TenantId.PLATFORM.value) ||
        key == SnapshotKey.App(controlApp.value)

    /** Why [request] would not be permitted with [candidate] in place of [key]; null when it would be. */
    private suspend fun refusal(
        request: EngineRequest,
        key: SnapshotKey,
        candidate: PolicyDocument,
        what: String
    ): LockoutCheck.Lockout? {
        val scope = request.scope
        val tenantKey = SnapshotKey.Tenant(scope.tenant.value)
        val tenant = if (tenantKey == key) candidate else retained.held(tenantKey)?.document
        val heads = simulator.keys(
            scope,
            tenant
        ).associateWith { if (it == key) Held(candidate, null) else retained.held(it) }
        val documents = HeldDocuments(
            heads[SnapshotKey.Platform],
            heads.entries.firstOrNull { it.key is SnapshotKey.App }?.value,
            heads.entries.firstOrNull { it.key is SnapshotKey.Tenant }?.value,
        )
        return when (val decision = simulator.decide(request, documents).decision) {
            is Decision.Permit -> null
            else -> LockoutCheck.Lockout("$what would be ${describe(decision)}")
        }
    }

    private fun describe(decision: Decision): String = when (decision) {
        is Decision.Deny -> when (val by = decision.by) {
            null -> "denied: no statement permits it"
            is StatementRef.Policy -> "denied by policy ${by.policy.value}, statement ${by.index}"
            else -> "denied ($by)"
        }
        else -> "${decision::class.simpleName?.lowercase()}: it could not be decided"
    }
}
