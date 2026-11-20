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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RC1-34 review: the tenant bindings an app change widens are shown to the approver, but what is approved is widening
 * the policy. A tenant binding added or removed after the approval does not send the change back, and none of them
 * alone needs anyone.
 */
class InformationalEscalationTest {
    private val authority = ChangeAuthority(PERMIT_ALL, revocations = RevocationReader.NONE)
    private val pia = Actor(TenantId.PLATFORM, PrincipalId("pia"))
    private val widen =
        Escalation("document app:order", Escalation.Reason.NOT_ANALYZED, "only the trust root publishes it alone")

    private fun binding(id: String) = Escalation("binding $id of [tenant:acme]", Escalation.Reason.TENANT_BINDINGS, "")

    private fun change(covers: List<String>) = ChangeRequest(
        id = ChangeId("c1"),
        key = me.ahoo.cosec.spi.snapshot.SnapshotKey.App("order"),
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
        approval = Approval(pia, StoreFixtures.START, "d", covers, NO_MEMBERSHIPS),
    )

    @Test
    fun `a tenant binding added after the approval needs no new one`() {
        val approved = change(listOf(widen.subject, binding("readers").subject))
        authority.approvalProblem(
            approved,
            listOf(widen, binding("readers"), binding("writers")),
            PLATO,
            StoreFixtures.START
        )
            .assert().isNull()
        // What was approved still has to be covered.
        authority.approvalProblem(change(listOf(binding("readers").subject)), listOf(widen), PLATO, StoreFixtures.START)
            .assert().isEqualTo("the approval does not cover what it now needs")
    }

    @Test
    fun `only tenant bindings need no approval at all`() = runBlocking<Unit> {
        val none = change(emptyList()).copy(approval = null)
        authority.approvalProblem(none, listOf(binding("readers")), PLATO, StoreFixtures.START).assert().isNull()
        authority.approverProblem(none, listOf(binding("readers")), PLATO).assert().isNull()
        authority.approverProblem(none, listOf(widen), PLATO).assert().isEqualTo("it needs an approval")
    }

    private val tenants = Escalation(
        "tenants binding [perm:order/read] in app order",
        Escalation.Reason.TENANT_BINDINGS,
        "statements [0] widen what it grants in up to 50000 tenant documents that bind it: [...]",
    )

    @Test
    fun `every reason the grant analysis gives is a reason of its own name`() {
        me.ahoo.cosec.control.grant.UnknownReason.entries.map { Escalation.Reason.of(it.name).name }
            .assert().isEqualTo(me.ahoo.cosec.control.grant.UnknownReason.entries.map { it.name })
    }

    @Test
    fun `only the tenants' entry is informational`() {
        Escalation.Reason.entries.filter { Escalation("x", it).informational }
            .assert().containsExactly(Escalation.Reason.TENANT_BINDINGS)
        // A name this version does not know needs an approval, as anything not known does.
        Escalation.Reason.of("TENANT_BINDING").assert().isEqualTo(Escalation.Reason.UNKNOWN)
    }

    @Test
    fun `an aggregated tenant entry never stands in for the approval the widening needs`() = runBlocking<Unit> {
        val needed = listOf(widen, tenants)
        authority.approvalProblem(change(listOf(tenants.subject)), needed, PLATO, StoreFixtures.START)
            .assert().isEqualTo("the approval does not cover what it now needs")
        authority.approverProblem(change(emptyList()).copy(approval = null), needed, PLATO)
            .assert().isEqualTo("it needs an approval")
        authority.approverProblem(change(emptyList()).copy(approval = null), listOf(tenants), PLATO).assert().isNull()
    }
}
