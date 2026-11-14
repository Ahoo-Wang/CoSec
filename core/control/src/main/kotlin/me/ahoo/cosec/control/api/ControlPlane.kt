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
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.BreakGlass
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The control plane as a library (RFC 0007 §10.1, Q18). Every call names its [Operator], whom the host pipeline
 * authenticated, and is authorized like a write (RFC 0007 §7.2): the hard rule of tenant isolation first, then the
 * action on each document it touches. Reads need `cosec:read`; `simulate`, `explain` and `analyze` need their own verbs
 * on the simulated tenant's document, and show what they found in other documents only to a caller who may read them.
 */
@InternalCoSecApi
interface ControlPlane {
    suspend fun read(operator: Operator, key: SnapshotKey, revision: Long? = null): PolicyDocument

    suspend fun history(operator: Operator, key: SnapshotKey, before: Long? = null, limit: Int = DEFAULT_LIMIT): List<RevisionSummary>

    suspend fun diff(operator: Operator, key: SnapshotKey, from: Long, to: Long): DocumentDiff

    /**
     * Proposes a change of kind `PUBLISH`, the only kind a caller proposes (review K1): break-glass goes through
     * [breakGlass], a rollback through [rollback].
     */
    suspend fun propose(
        operator: Operator,
        key: SnapshotKey,
        base: Long,
        operations: List<Operation>,
        note: String,
        confirmations: List<Confirmation> = emptyList(),
    ): ChangeRequest

    suspend fun apply(operator: Operator, key: SnapshotKey, desired: PolicyDocument, mode: ApplyMode, note: String): ChangeRequest

    suspend fun rollback(
        operator: Operator,
        key: SnapshotKey,
        toRevision: Long,
        note: String,
        confirmations: List<Confirmation> = emptyList(),
    ): ChangeRequest

    suspend fun rebase(operator: Operator, change: ChangeId): ChangeRequest

    suspend fun startShadow(operator: Operator, change: ChangeId): ChangeRequest

    suspend fun skipShadow(operator: Operator, change: ChangeId, reason: String): ChangeRequest

    suspend fun approve(operator: Operator, change: ChangeId, candidate: String): ChangeRequest

    suspend fun reject(operator: Operator, change: ChangeId, reason: String): ChangeRequest

    suspend fun abandon(operator: Operator, change: ChangeId): ChangeRequest

    suspend fun publish(operator: Operator, change: ChangeId, ack: ShadowAck? = null): ChangeRequest

    suspend fun get(operator: Operator, change: ChangeId): ChangeRequest

    val breakGlass: BreakGlass

    suspend fun simulate(operator: Operator, request: SimulatedRequest, candidate: ChangeId? = null): Simulation

    suspend fun explain(operator: Operator, request: SimulatedRequest, at: ViewVersion? = null): Explanation

    suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport

    suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation

    @InternalCoSecApi
    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 200
    }
}
