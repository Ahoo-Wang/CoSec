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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.OnMissing
import me.ahoo.cosec.engine.flat.OnMissingConfirmation
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0007 §9.5 (N6): a DENY that lapses while its attributes cannot be fetched is published only with a confirmation
 * of its exact content; the control plane writes who confirmed it, and nobody else can.
 */
class NomatchConfirmationTest {
    private val harness = ChangeHarness()
    private val changes = harness.changes
    private val inFence = Condition.Eq("ext.geo.inFence", Literal.Bool(true))

    private fun lapsing(
        action: String = "*",
        confirmed: OnMissingConfirmation? = null,
        policyCondition: Condition? = null,
    ) = PolicyDefinition(
        PolicyId("fence"),
        false,
        policyCondition,
        listOf(
            StatementSource(
                "",
                Effect.DENY,
                listOf(action),
                condition = inFence,
                onMissing = OnMissing.NOMATCH,
                onMissingConfirmed = confirmed
            )
        ),
    )

    private suspend fun publish(key: SnapshotKey, operations: List<Operation>, confirmations: List<Confirmation> = emptyList()): ChangeRequest {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(operatorFor(key), key, head, operations, "test", confirmations = confirmations)
        if (change.state != ChangeState.CHECKED) return change
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id)
    }

    private suspend fun statement() = harness.revisions.head(ACME)!!.document.policies.single().statements.single()

    @Test
    fun `a lapsing deny publishes only with a confirmation of its content, recorded by the control plane`() = runBlocking<Unit> {
        publish(
            SnapshotKey.Platform,
            listOf(Operation.SetAttributes(listOf(Attribute.external("ext.geo.inFence", AttributeType.BOOL))))
        )
        val digest = StatementDigest.of(lapsing().statements.single())

        // Unconfirmed, or confirmed for other content: it waits for a confirmation, and would not compile.
        val unconfirmed = publish(ACME, listOf(Operation.PutPolicy(lapsing())))
        unconfirmed.state.assert().isEqualTo(ChangeState.DRAFT)
        unconfirmed.errors.map { it.check to it.status }.assert().contains("V5" to 428)
        val stale =
            publish(
                ACME,
                listOf(Operation.PutPolicy(lapsing())),
                listOf(Confirmation(PolicyId("fence"), 0, "0".repeat(64)))
            )
        stale.state.assert().isEqualTo(ChangeState.DRAFT)

        // A confirmation an author writes into the document is not one: it is dropped.
        val forged =
            publish(
                ACME,
                listOf(Operation.PutPolicy(lapsing(confirmed = OnMissingConfirmation("(platform)", "plato", "c0"))))
            )
        forged.state.assert().isEqualTo(ChangeState.DRAFT)

        val confirmed =
            publish(ACME, listOf(Operation.PutPolicy(lapsing())), listOf(Confirmation(PolicyId("fence"), 0, digest)))
        confirmed.state.assert().isEqualTo(ChangeState.PUBLISHED)
        confirmed.confirmations.single().digest.assert().isEqualTo(digest)
        statement().onMissingConfirmed.assert().isEqualTo(
            OnMissingConfirmation(ALICE.tenant.value, ALICE.principal.value, confirmed.id.value)
        )

        // Unchanged, it keeps its confirmation; changed, it needs a new one.
        publish(
            ACME,
            listOf(Operation.PutPolicy(lapsing()), Operation.PutBinding(bind("b", "fence")))
        ).state.assert().isEqualTo(ChangeState.PUBLISHED)
        statement().onMissingConfirmed!!.change.assert().isEqualTo(confirmed.id.value)
        val changed = publish(ACME, listOf(Operation.PutPolicy(lapsing(action = "order:*"))))
        changed.state.assert().isEqualTo(ChangeState.DRAFT)
        changed.errors.single { it.check == "V5" }.path.assert().isEqualTo("$.policies[0].statements[0]")
    }

    /** Review K4: the policy condition guards the statement as much as its own, so the confirmation covers it too. */
    @Test
    fun `a confirmation does not survive a change of its policy's condition`() = runBlocking<Unit> {
        publish(
            SnapshotKey.Platform,
            listOf(Operation.SetAttributes(listOf(Attribute.external("ext.geo.inFence", AttributeType.BOOL))))
        )
        val digest = StatementDigest.of(lapsing().statements.single())
        publish(ACME, listOf(Operation.PutPolicy(lapsing())), listOf(Confirmation(PolicyId("fence"), 0, digest)))
            .state.assert().isEqualTo(ChangeState.PUBLISHED)
        val guarded = lapsing(policyCondition = Condition.Eq("subject.authenticated", Literal.Bool(true)))
        val narrowed = publish(ACME, listOf(Operation.PutPolicy(guarded)))
        narrowed.state.assert().isEqualTo(ChangeState.DRAFT)
        narrowed.errors.single { it.check == "V5" }.path.assert().isEqualTo("$.policies[0].statements[0]")
        val newDigest = StatementDigest.of(guarded.statements.single(), guarded.condition)
        newDigest.assert().isNotEqualTo(digest)
        publish(ACME, listOf(Operation.PutPolicy(guarded)), listOf(Confirmation(PolicyId("fence"), 0, newDigest)))
            .state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `the digest ignores a confirmation and changes with the content`() {
        val plain = lapsing().statements.single()
        StatementDigest.of(
            lapsing(confirmed = OnMissingConfirmation("t", "p", "c")).statements.single()
        ).assert().isEqualTo(StatementDigest.of(plain))
        StatementDigest.of(
            lapsing(action = "order:*").statements.single()
        ).assert().isNotEqualTo(StatementDigest.of(plain))
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { Confirmation(PolicyId("p"), 0, "d", "other") }
    }
}
