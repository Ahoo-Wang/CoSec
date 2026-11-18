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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/**
 * A change request (RFC 0007 §6.1): a base and the entry-level operations on it, with what the last check found.
 *
 * [candidate] is the digest of the candidate the last clean check accepted
 * ([me.ahoo.cosec.control.lifecycle.CandidateDigest]); approvals and shadow reports are bound to it, so anything that
 * changes the candidate voids them. [checkedWith] are the heads of the other documents the candidate was compiled and
 * analyzed with; publishing checks again on the current heads, and once it has written, looks whether one of them moved
 * in between (RC1-33).
 */
@ExperimentalCoSecApi
data class ChangeRequest(
    val id: ChangeId,
    val key: SnapshotKey,
    val base: Base,
    val operations: List<Operation>,
    val author: Actor,
    val note: String,
    val kind: ProvenanceKind,
    val state: ChangeState,
    val findings: List<Finding>,
    val candidate: String?,
    val checkedWith: Map<SnapshotKey, SnapshotDigest?>,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Why the shadow window was skipped (`cosec:skip-shadow`, RFC 0007 Q1); `null` when it was not. */
    val skipShadow: String? = null,
    /** The revision it published; set once [state] is [ChangeState.PUBLISHED]. */
    val publishedRevision: Long? = null,
    /** What needs approval before it publishes (RFC 0007 §7.5): its author's last check found these. */
    val escalations: List<Escalation> = emptyList(),
    /** The approval it has; `null` until one is given, and again after anything it was given for changes. */
    val approval: Approval? = null,
    /** Its shadow window (RFC 0007 §6.4): `null` until it stages, and when the window was skipped. */
    val shadow: ShadowState? = null,
    /** What its author confirmed (RFC 0007 §9.5): `deny-nomatch` statements, by digest. */
    val confirmations: List<Confirmation> = emptyList(),
    /** The app list its last check read (RC1-4, RC1-33); `null` when that check did not read it. */
    val checkedApps: CheckedApps? = null,
) {
    val errors: List<Finding> get() = findings.filter { it.severity == Severity.ERROR }
}
