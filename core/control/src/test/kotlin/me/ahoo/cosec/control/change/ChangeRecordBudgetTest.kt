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
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.ChangeRecordBudget
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.control.lifecycle.UnknownEscalations
import me.ahoo.cosec.control.store.RetentionPolicy
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.allOpenChanges
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-83` (DR2-E-2): a change record is written with at most [ChangeRecordBudget.recordCap] bytes, so a document's
 * ended records weigh at most `max-count` of them, and what one tenant document stores is stated, derived from
 * `max-count` and the document cap only.
 */
class ChangeRecordBudgetTest {
    private fun change(operations: List<Operation> = emptyList(), escalations: List<Escalation> = emptyList()) =
        ChangeRequest(
            id = ChangeId("c1"),
            key = ACME,
            base = Base.NONE,
            operations = operations,
            author = ALICE,
            note = "",
            kind = ProvenanceKind.PUBLISH,
            state = ChangeState.CHECKED,
            findings = emptyList(),
            candidate = "d",
            checkedWith = emptyMap(),
            createdAt = StoreFixtures.START,
            updatedAt = StoreFixtures.START,
            escalations = escalations,
        )

    @Test
    fun `the bounds are derived from max-count and the document cap - DR2-E-2`() {
        val retention = RetentionPolicy()
        val cap = RetentionPolicy.DEFAULT_DOCUMENT_CAP
        ChangeRecordBudget.recordCap(cap).assert().isEqualTo(ControlDerived.MAX_BODY_BYTES + cap)
        ChangeRecordBudget.recordCap(cap).assert().isEqualTo(18L * MB)
        ChangeRecordBudget.endedBytes(retention).assert().isEqualTo(50 * 18L * MB)
        ChangeRecordBudget.documentBytes(retention).assert().isEqualTo((400 + 400 + 900 + 8) * MB)
        ChangeRecordBudget.describe(retention).assert()
            .contains("one at most 18MB")
            .contains("one document stores at most 1708MB")
            .contains("revisions 400MB, open changes 400MB, ended changes 900MB")
            .contains("times the number of tenant documents")
        // max-count lowers each of the three record sets in proportion.
        ChangeRecordBudget.documentBytes(RetentionPolicy(maxCount = 10)).assert().isEqualTo((80 + 80 + 180 + 8) * MB)
    }

    @Test
    fun `a record past its cap is not written - DR2-E-2`() {
        val change = change(listOf(Operation.PutPolicy(allow("p", resource = "order/" + "x".repeat(1_000)))))
        val size = ChangeRecordBudget.requireWithin(change, Long.MAX_VALUE)
        ChangeRecordBudget.requireWithin(change, size).assert().isEqualTo(size)
        val refused = try {
            ChangeRecordBudget.requireWithin(change, size - 1)
            error("not refused")
        } catch (e: ChangeRejectedException) {
            e
        }
        refused.status.assert().isEqualTo(422)
        refused.message.assert().contains("$size bytes").contains("over the ${size - 1}")
    }

    /**
     * What makes the stated product true: the largest record a proposal makes, its operations the largest body and its
     * escalations at the grant analysis's bound, leaves the record a document's worth for its findings.
     */
    @Test
    fun `the largest operations and escalations leave a document's worth for the findings - DR2-E-2`() {
        val resource = "order/" + "x".repeat(2_000)
        val statements = (ControlDerived.MAX_BODY_BYTES / (resource.length + 40)).toInt()
        val statement = StatementSource("", Effect.ALLOW, listOf("order:read"), listOf(ResourceSource(resource)))
        val policy = PolicyDefinition(PolicyId("p"), false, null, List(statements) { statement })
        val operations = listOf(Operation.PutPolicy(policy))
        val escalations = List(UnknownEscalations.MAX_GROUPS + 2) {
            Escalation("s".repeat(UnknownEscalations.MAX_TEXT + 160), Escalation.Reason.NOT_COVERED, "d".repeat(928))
        }
        val size = ChangeRecordBudget.requireWithin(change(operations, escalations), Long.MAX_VALUE)
        size.assert().isLessThanOrEqualTo(ControlDerived.MAX_BODY_BYTES + MB)
        (ChangeRecordBudget.recordCap(RetentionPolicy.DEFAULT_DOCUMENT_CAP) - size).assert()
            .isGreaterThanOrEqualTo(RetentionPolicy.DEFAULT_DOCUMENT_CAP - MB)
    }

    @Test
    fun `a proposal whose record would pass the cap is refused, and nothing is stored - DR2-E-2`() = runBlocking<Unit> {
        // A document cap of 2,000 bytes: a record of a policy larger than the body a route reads passes it, as only a
        // caller of the plane in process can propose.
        val harness = ChangeHarness(options = ChangeOptions(maxDocumentBytes = 2_000, shadow = IMMEDIATE_WINDOW))
        val huge = allow("huge", resource = "order/" + "x".repeat((ControlDerived.MAX_BODY_BYTES + 4_000).toInt()))
        val refused = try {
            harness.changes.propose(PLATO, ACME, 0, listOf(Operation.PutPolicy(huge)), "huge")
            error("not refused")
        } catch (e: ChangeRejectedException) {
            e
        }
        refused.status.assert().isEqualTo(422)
        harness.memory.allOpenChanges().assert().isEmpty()
    }

    companion object {
        private const val MB = 1024L * 1024
    }
}
