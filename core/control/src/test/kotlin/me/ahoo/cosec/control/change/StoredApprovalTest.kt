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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `APR-1` at publish: the approval a change record carries counts only when someone other than its author and its
 * publisher gave it, for the candidate being published. Approving refuses the author and another candidate already, and
 * no step of today's flow keeps an approval across a new candidate, so only these direct checks of the stored record
 * fail when the publish-time check goes (two mutants that removed it survived every flow test).
 */
class StoredApprovalTest {
    private val authority = ChangeAuthority(PERMIT_ALL, revocations = RevocationReader.NONE)
    private val pia = Actor(TenantId.PLATFORM, PrincipalId("pia"))
    private val quinn = person(Actor(TenantId.PLATFORM, PrincipalId("quinn")))
    private val widen =
        Escalation("document app:order", Escalation.Reason.NOT_ANALYZED, "only the trust root publishes it alone")

    private fun approvedBy(approver: Actor, candidate: String = "d") = ChangeRequest(
        id = ChangeId("c1"),
        key = SnapshotKey.App("order"),
        base = Base.NONE,
        operations = emptyList(),
        author = PLATO.actor,
        note = "",
        kind = ProvenanceKind.PUBLISH,
        state = ChangeState.READY,
        findings = emptyList(),
        candidate = "d",
        checkedWith = emptyMap(),
        createdAt = StoreFixtures.START,
        updatedAt = StoreFixtures.START,
        escalations = listOf(widen),
        approval = Approval(approver, StoreFixtures.START, candidate, listOf(widen.subject), NO_MEMBERSHIPS),
    )

    private fun problem(change: ChangeRequest, publisher: Operator = quinn): String? =
        authority.approvals.approvalProblem(change, change.escalations, publisher, StoreFixtures.START)

    @Test
    fun `a stored approval counts only when another person approved the candidate published`() {
        problem(approvedBy(pia)).assert().isNull()
        problem(approvedBy(PLATO.actor)).assert().isEqualTo("the approval is the author's or the publisher's own")
        problem(approvedBy(quinn.actor)).assert().isEqualTo("the approval is the author's or the publisher's own")
        problem(approvedBy(pia, candidate = "e")).assert().isEqualTo("the approval is for another candidate")
    }
}
