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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * RFC 0007 §6.3 (`CTL-1`): the control plane compiles every candidate with the data plane's compiler, strictly, with
 * the platform's limits and attributes (N3), and a candidate with any `Broken` entry never reaches the head.
 */
class PublishCompileTest {
    private val harness = ChangeHarness()
    private val changes = harness.changes

    private suspend fun published(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(operatorFor(key), key, head, operations.toList(), "test")
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id).also { it.state.assert().isEqualTo(ChangeState.PUBLISHED) }
    }

    @Test
    fun `a candidate with a broken entry stays a draft and never reaches the head`() = runBlocking<Unit> {
        published(ACME, Operation.PutPolicy(allow("ok")))
        val change = changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(broken("bad"))), "broken")
        change.state.assert().isEqualTo(ChangeState.DRAFT)
        change.errors.single().check.assert().isEqualTo("COMPILE")
        change.errors.single().path.assert().isEqualTo("$.policies[1].statements[0].condition.eq[0]")
        change.errors.single().status.assert().isEqualTo(422)
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.skipShadow(ALICE_OP, change.id, "x") } }
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.publish(ALICE_OP, change.id) } }
        harness.revisions.head(ACME)!!.revision.assert().isEqualTo(1)
    }

    @Test
    fun `references must resolve in the candidate or the platform`() = runBlocking<Unit> {
        published(SnapshotKey.Platform, Operation.PutPolicy(allow("shared")))
        published(ACME, Operation.PutBinding(bind("a", "shared")))
        val dangling = changes.propose(
            ALICE_OP,
            ACME,
            1,
            listOf(Operation.PutBinding(bind("c", "nowhere"))),
            "dangling"
        )
        dangling.errors.map { it.check to it.path }.assert().containsExactly("REFERENCES" to "$.bindings[1].policy")
        harness.revisions.head(ACME)!!.revision.assert().isEqualTo(1)
    }

    @Test
    fun `conditions compile against the platform's attributes and limits`() = runBlocking<Unit> {
        val reading = allow("risk", condition = Condition.Eq("ext.risk.level", Literal.Str("low")))
        changes.propose(
            ALICE_OP,
            ACME,
            0,
            listOf(Operation.PutPolicy(reading)),
            "undeclared"
        ).state.assert().isEqualTo(ChangeState.DRAFT)
        published(
            SnapshotKey.Platform,
            Operation.SetAttributes(listOf(Attribute.external("ext.risk.level", AttributeType.STRING)))
        )
        changes.propose(
            ALICE_OP,
            ACME,
            0,
            listOf(Operation.PutPolicy(reading)),
            "declared"
        ).let { it.state.assert().describedAs("${it.findings}").isEqualTo(ChangeState.CHECKED) }

        val wide = allow(
            "wide",
            condition = Condition.In("scope.app", listOf(Literal.Str("a"), Literal.Str("b"), Literal.Str("c")))
        )
        changes.propose(
            ALICE_OP,
            ACME,
            0,
            listOf(Operation.PutPolicy(wide)),
            "fits"
        ).state.assert().isEqualTo(ChangeState.CHECKED)
        val tight = ConditionLimits(maxInValues = 2)
        val platform = changes.propose(PLATO, SnapshotKey.Platform, 1, listOf(Operation.SetLimits(tight)), "tighten")
        // The other documents are not compiled again: a warning, not an error.
        platform.state.assert().isEqualTo(ChangeState.CHECKED)
        platform.findings.single().severity.assert().isEqualTo(Severity.WARNING)
        changes.skipShadow(PLATO, platform.id, "test")
        changes.publish(PLATO, platform.id)
        changes.propose(
            ALICE_OP,
            ACME,
            0,
            listOf(Operation.PutPolicy(wide)),
            "too wide"
        ).state.assert().isEqualTo(ChangeState.DRAFT)
    }

    @Test
    fun `a candidate that stops compiling after its check is not published`() = runBlocking<Unit> {
        published(
            SnapshotKey.Platform,
            Operation.SetAttributes(listOf(Attribute.external("ext.risk.level", AttributeType.STRING)))
        )
        val reading = allow("risk", condition = Condition.Eq("ext.risk.level", Literal.Str("low")))
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(reading)), "declared")
        changes.skipShadow(ALICE_OP, change.id, "test")
        // The platform drops the attribute before the tenant change publishes.
        published(SnapshotKey.Platform, Operation.SetAttributes(null))
        val outcome = changes.publish(ALICE_OP, change.id)
        outcome.state.assert().isEqualTo(ChangeState.DRAFT)
        outcome.errors.map { it.check }.assert().containsExactly("COMPILE")
        harness.revisions.head(ACME).assert().isNull()
    }

    @Test
    fun `removing a platform policy a tenant binds is refused`() = runBlocking<Unit> {
        published(SnapshotKey.Platform, Operation.PutPolicy(allow("shared")), Operation.PutPolicy(allow("other")))
        published(ACME, Operation.PutBinding(bind("a", "shared")))
        val removal = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            1,
            listOf(Operation.RemovePolicy(me.ahoo.cosec.model.PolicyId("shared"))),
            "remove"
        )
        removal.errors.map { it.check }.assert().containsExactly("REFERENCES")
        harness.revisions.head(
            SnapshotKey.Platform
        )!!.document.policies.map { it.id.value }.assert().containsExactly("shared", "other")
    }
}
