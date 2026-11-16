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
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.lifecycle.BreakGlass
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The [ControlPlane] over [changes] and [revisions], authorizing with [authority]. The change flow is `changes`; what it
 * reads and says is in [DocumentReads], [DecisionQueries] and [AccessAnalysis], each of which holds the authority check of
 * every method it answers, as the first thing it does.
 */
@InternalCoSecApi
class DefaultControlPlane(
    private val changes: Changes,
    private val revisions: Revisions,
    private val authority: ChangeAuthority,
    private val simulator: Simulator = Simulator(),
) : ControlPlane {
    override val breakGlass: BreakGlass = BreakGlass(changes, revisions)

    private val retained = RetainedDocuments(revisions, simulator.cache)
    private val reads = DocumentReads(authority, revisions, changes, retained)
    private val decisions = DecisionQueries(changes, authority, simulator, retained)
    private val analysis = AccessAnalysis(authority, retained)

    override suspend fun read(operator: Operator, key: SnapshotKey, revision: Long?): PolicyDocument =
        reads.read(operator, key, revision)

    override suspend fun history(operator: Operator, key: SnapshotKey, before: Long?, limit: Int): List<RevisionSummary> =
        reads.history(operator, key, before, limit)

    override suspend fun diff(operator: Operator, key: SnapshotKey, from: Long, to: Long): DocumentDiff =
        reads.diff(operator, key, from, to)

    override suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation =
        reads.propagation(operator, key, revision)

    override suspend fun simulate(operator: Operator, request: SimulatedRequest, candidate: ChangeId?): Simulation =
        decisions.simulate(operator, request, candidate)

    override suspend fun explain(operator: Operator, request: SimulatedRequest, at: ViewVersion?): Explanation =
        decisions.explain(operator, request, at)

    override suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport =
        analysis.analyze(operator, query)

    override suspend fun propose(
        operator: Operator,
        key: SnapshotKey,
        base: Long,
        operations: List<Operation>,
        note: String,
        confirmations: List<Confirmation>,
    ): ChangeRequest = changes.propose(operator, key, base, operations, note, ProvenanceKind.PUBLISH, confirmations)

    override suspend fun apply(operator: Operator, key: SnapshotKey, desired: PolicyDocument, mode: ApplyMode, note: String): ChangeRequest {
        authority.require(operator, key, ControlActions.READ)
        return changes.apply(operator, key, desired, mode, note)
    }

    override suspend fun rollback(
        operator: Operator,
        key: SnapshotKey,
        toRevision: Long,
        note: String,
        confirmations: List<Confirmation>,
    ): ChangeRequest {
        authority.require(operator, key, ControlActions.READ)
        return changes.rollback(operator, key, toRevision, note, confirmations)
    }

    override suspend fun rebase(operator: Operator, change: ChangeId): ChangeRequest = changes.rebase(operator, change)

    override suspend fun startShadow(operator: Operator, change: ChangeId): ChangeRequest = changes.stage(
        operator,
        change
    )

    override suspend fun skipShadow(operator: Operator, change: ChangeId, reason: String): ChangeRequest =
        changes.skipShadow(operator, change, reason)

    override suspend fun approve(operator: Operator, change: ChangeId, candidate: String): ChangeRequest =
        changes.approve(operator, change, candidate)

    override suspend fun reject(operator: Operator, change: ChangeId, reason: String): ChangeRequest =
        changes.reject(operator, change, reason)

    override suspend fun abandon(operator: Operator, change: ChangeId): ChangeRequest = changes.abandon(
        operator,
        change
    )

    override suspend fun publish(operator: Operator, change: ChangeId, ack: ShadowAck?): ChangeRequest =
        changes.publish(operator, change, ack)

    /** A change is read like its document: one the caller may not read is not there (404), so its id tells nothing. */
    override suspend fun get(operator: Operator, change: ChangeId): ChangeRequest = changes.visible(operator, change)
}
