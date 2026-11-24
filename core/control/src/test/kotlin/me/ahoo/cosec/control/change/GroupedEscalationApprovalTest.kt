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
import me.ahoo.cosec.control.grant.GrantAnalysis
import me.ahoo.cosec.control.grant.GrantFragment
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.Grantee
import me.ahoo.cosec.control.grant.ScopeSet
import me.ahoo.cosec.control.grant.UnknownReason
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.UnknownEscalations
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * CTL-88, DR2-E-1: a statement's unknown fragments are one escalation, and an approval of it covers exactly the
 * fragments it was shown. When the analysis on the current documents finds one more, the publish needs a new approval,
 * as it did when each fragment was an escalation of its own.
 */
class GroupedEscalationApprovalTest {
    private val authority = ChangeAuthority(PERMIT_ALL, revocations = RevocationReader.NONE)
    private val pia = Actor(TenantId.PLATFORM, PrincipalId("pia"))
    private val acme = TenantId("acme")

    private fun fragment(action: String) = GrantFragment(
        acme,
        PolicyId("p"),
        0,
        ActionPattern.parse(action),
        ResourcePattern.parse("order/**"),
        null,
        Grantee.Bound(
            Binding(
                BindingId("b"),
                SubjectRef.Role(acme, RoleId("r")),
                BindingScope(Match.Any, Match.Any),
                PolicyId("p")
            )
        ),
        ScopeSet.of(Match.Any, Match.Any),
    )

    private fun escalations(vararg actions: String) = UnknownEscalations.of(
        ACME,
        GrantAnalysis(actions.map { fragment(it) to GrantVerdict.Unknown(UnknownReason.NOT_COVERED, null, "", null) }),
    )

    private fun approved(covers: List<Escalation>) = ChangeRequest(
        id = ChangeId("c1"),
        key = ACME,
        base = Base.NONE,
        operations = emptyList(),
        author = ALICE,
        note = "",
        kind = ProvenanceKind.PUBLISH,
        state = ChangeState.READY,
        findings = emptyList(),
        candidate = "d",
        checkedWith = emptyMap(),
        createdAt = StoreFixtures.START,
        updatedAt = StoreFixtures.START,
        approval = Approval(pia, StoreFixtures.START, "d", covers.map { it.subject }, NO_MEMBERSHIPS),
    )

    @Test
    fun `an approval of a statement's fragments covers them, and not one more of them`() {
        val shown = escalations("order:read", "order:write")
        val change = approved(shown)
        authority.approvals.approvalProblem(change, shown, PLATO, StoreFixtures.START).assert().isNull()
        authority.approvals.approvalProblem(
            change,
            escalations("order:read", "order:write", "order:delete"),
            PLATO,
            StoreFixtures.START,
        ).assert().isEqualTo("the approval does not cover what it now needs")
        authority.approvals.approvalProblem(
            change,
            escalations("order:read", "order:delete"),
            PLATO,
            StoreFixtures.START,
        ).assert().isEqualTo("the approval does not cover what it now needs")
    }
}
