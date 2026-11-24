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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeEventSize
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.grant.ComparisonBudget
import me.ahoo.cosec.control.lifecycle.UnknownEscalations
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * CTL-88, DR2-E-1: what one proposal records is bounded whatever the change's shape. A tenant administrator proposes a
 * change near the fragment bound, none of it held: one statement of 40 actions by 40 long resources, bound 45 times, and
 * a policy of more statements than the escalations list. The change needs an approval, as before, and its record
 * and its event stay small.
 */
class EscalationBoundsTest {
    private val fixture = ControlPlaneTest()

    @Test
    fun `a proposal of tens of thousands of unproven fragments records a bounded list - CTL-88`() = runBlocking<Unit> {
        val long = "x".repeat(1_000)
        val wide = PolicyDefinition(
            PolicyId("wide"),
            false,
            null,
            listOf(
                StatementSource(
                    "",
                    Effect.ALLOW,
                    (1..ACTIONS).map { "billing:a$it" },
                    (1..RESOURCES).map { ResourceSource("billing/$long/$it") },
                ),
            ),
        )
        val many = PolicyDefinition(
            PolicyId("many"),
            false,
            null,
            (1..STATEMENTS).map {
                StatementSource(
                    "",
                    Effect.ALLOW,
                    listOf("billing:s$it"),
                    listOf(ResourceSource("billing/x"))
                )
            },
        )
        fun bind(id: String, policy: PolicyId) = Operation.PutBinding(
            Binding(
                BindingId(id),
                SubjectRef.Role(ControlPlaneTest.ACME, RoleId(id)),
                BindingScope(Match.Any, Match.Any),
                policy
            )
        )
        val operations = listOf(Operation.PutPolicy(wide), Operation.PutPolicy(many), bind("many", many.id)) +
            (1..BINDINGS).map { bind("r$it", wide.id) }
        val fragments = ACTIONS * RESOURCES * BINDINGS + STATEMENTS
        fragments.toLong().assert().isLessThanOrEqualTo(ComparisonBudget.MAX_FRAGMENTS)

        val change = fixture.plane.propose(fixture.alice, ControlPlaneTest.TENANT, 1, operations, "wide")

        // Checked, and waiting for an approval once its window closes: every escalation needs one.
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        val escalations = change.escalations
        escalations.none { it.informational }.assert().isTrue()
        escalations.size.assert().isEqualTo(UnknownEscalations.MAX_GROUPS + 1)
        escalations.last().reason.assert().isEqualTo(Escalation.Reason.TOO_LARGE)
        // Every fragment is counted in one entry: none is left out of what the approval is for.
        escalations.sumOf { counted.find(it.subject)!!.groupValues[1].toInt() }.assert().isEqualTo(fragments)
        ChangeRequestCodec.encode(change).size.assert().isLessThan(MAX_RECORD_BYTES)
        val checked = fixture.events.single { it.kind == ChangeEvent.Kind.CHECKED && it.change == change.id }
        ChangeEventSize.of(checked).assert().isLessThan(MAX_RECORD_BYTES.toLong() * 2)
    }

    companion object {
        private val counted = Regex("""(\d+) (?:more )?fragments?""")
        const val ACTIONS = 40
        const val RESOURCES = 40
        const val BINDINGS = 45
        const val STATEMENTS = 250

        /** The escalations at their bound, with the change's own operations: the long resources, 40 of 1 KB. */
        const val MAX_RECORD_BYTES = 512 * 1024
    }
}
