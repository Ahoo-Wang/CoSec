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
import me.ahoo.cosec.control.change.Approval
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOrigin
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.CheckedApps
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.NO_MEMBERSHIPS
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.control.change.ShadowState
import me.ahoo.cosec.control.change.ShadowSummary
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/**
 * Changes of every shape the control API answers (R2C-11): the least a record holds, every optional member, and each
 * optional object without its own optional members. A shape test reads every member from them, and the contract derives
 * a member's presence from them: `always` when every sample whose parent holds it holds it too.
 */
internal object ChangeSamples {
    private val at = Instant.parse("2026-11-20T00:00:00Z")
    private val digest = SnapshotDigest(ByteArray(32) { 1 })
    private val pia = Actor(TenantId.PLATFORM, PrincipalId("pia"))
    private val escalation = Escalation("policy wide", Escalation.Reason.NOT_COVERED, "widens order:read")

    val minimal = ChangeRequest(
        id = ChangeId("c1"),
        key = SnapshotKey.Tenant("acme"),
        base = Base.NONE,
        operations = emptyList(),
        author = PLATO.actor,
        note = "",
        kind = ProvenanceKind.PUBLISH,
        state = ChangeState.DRAFT,
        findings = emptyList(),
        candidate = null,
        checkedWith = emptyMap(),
        createdAt = at,
        updatedAt = at,
    )

    /** Every member the record can hold, the store's own bookkeeping included. */
    val full = minimal.copy(
        base = Base(3, digest),
        operations = listOf(Operation.PutPolicy(allow("more"))),
        state = ChangeState.PUBLISHED,
        findings = listOf(Finding("REFERENCES", "$.roles[0]", "a role may mean more", 200, Severity.WARNING)),
        candidate = "d",
        checkedWith = mapOf(SnapshotKey.Platform to digest, SnapshotKey.App("order") to null),
        skipShadow = "hurry",
        publishedRevision = 4,
        origin = ChangeOrigin(false, ChangeOrigin.Via("jwt", "token-1", "req-1"), "10.0.0.7"),
        escalations = listOf(escalation),
        approval = Approval(
            pia,
            at,
            "d",
            listOf(escalation.subject),
            NO_MEMBERSHIPS,
            attributes = mapOf("department" to "ops"),
            credentialId = "token-pia",
            issuedAt = at,
            kind = Identity.Credential.Kind.PERSON,
        ),
        shadow = ShadowState(
            "c1@1",
            at,
            ShadowSummary(mapOf("SAME" to 3L, "NARROWED" to 1L), at, insufficient = true, blocked = false, setOf("i2")),
            reporters = setOf("i1", "i2"),
        ),
        confirmations = listOf(Confirmation(PolicyId("no-match"), 0, "e")),
        checkedApps = CheckedApps(digest),
    )

    /** Each optional object present, without its own optional members: a window still open, then one closed. */
    val open = minimal.copy(shadow = ShadowState("c1@1", at), approval = Approval(pia, at, "d", emptyList()))
    val closed = minimal.copy(
        shadow = ShadowState("c1@1", at, ShadowSummary(emptyMap(), at, insufficient = false, blocked = false)),
        origin = ChangeOrigin(true),
    )

    val all = listOf(minimal, full, open, closed)
}
