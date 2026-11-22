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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/**
 * A change, as `GET /changes/{id}` and every route that moves one answer it (`contract/control/change.json`): of
 * document [key], proposed on [base], in [state]. [candidate] is the digest of the document it would publish, once
 * checked; [publishedRevision] the revision it published. A member a later host adds is not read.
 */
@ExperimentalCoSecApi
// stays: one member per member of the answer, as contract/control/change.json lists them, so a reader finds each
@Suppress("LongParameterList")
class Change(
    val id: String,
    val key: SnapshotKey,
    val kind: ChangeKind,
    val state: ChangeState,
    val base: ChangeBase,
    val note: String,
    val author: Actor,
    val operations: List<Operation>,
    val findings: List<Finding>,
    val escalations: List<Escalation>,
    val candidate: String?,
    val publishedRevision: Long?,
    val skipShadow: String?,
    val shadow: Shadow?,
    val approval: Approval?,
    val origin: Origin?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean = other is Change && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Change",
        "id" to id,
        "key" to key,
        "kind" to kind,
        "state" to state,
        "base" to base,
        "note" to note,
        "author" to author,
        "operations" to operations,
        "findings" to findings,
        "escalations" to escalations,
        "candidate" to candidate,
        "publishedRevision" to publishedRevision,
        "skipShadow" to skipShadow,
        "shadow" to shadow,
        "approval" to approval,
        "origin" to origin,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )

    private fun parts(): List<Any?> = listOf(
        id, key, kind, state, base, note, author, operations, findings, escalations, candidate, publishedRevision,
        skipShadow, shadow, approval, origin, createdAt, updatedAt,
    )
}
