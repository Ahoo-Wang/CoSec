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
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.DocumentBudgets
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * `CTL-40`: for every budget, a candidate's count must be at most the larger of the limit and the count of the head it
 * replaces; one still over is a warning. `CTL-41`: a change a client proposes carries at most
 * `limits.maxChangeOperations` operations.
 */
class EntryBudgetsTest {
    private val harness = ChangeHarness()
    private val changes = harness.changes

    /** A document of [count] entries [entry] counts, and nothing else it counts; [tag] keeps two sets of ids apart. */
    private fun entries(
        entry: DocumentBudgets.Entry,
        count: Int,
        tag: String = "e"
    ): Pair<List<PolicyDefinition>, List<Binding>> =
        when (entry) {
            DocumentBudgets.Entry.POLICIES -> (0 until count).map { allow("$tag$it") } to emptyList()
            DocumentBudgets.Entry.STATEMENTS -> listOf(statements("$tag-s", count)) to emptyList()
            DocumentBudgets.Entry.ROLE_BINDINGS -> listOf(allow("$tag-p")) to (0 until count).map { bind("$tag$it", "$tag-p") }
            DocumentBudgets.Entry.ANYONE_BINDINGS -> listOf(allow("$tag-p")) to (0 until count).map { anyone("$tag$it", "$tag-p") }
        }

    private fun statements(id: String, count: Int) = PolicyDefinition(
        PolicyId(id),
        attachable = false,
        condition = null,
        statements = (0 until count).map { StatementSource("s$it", Effect.ALLOW, listOf("order:read"), emptyList()) },
    )

    private fun anyone(id: String, policy: String) =
        Binding(BindingId(id), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId(policy))

    private fun budgets(entry: DocumentBudgets.Entry, limit: Int) = when (entry) {
        DocumentBudgets.Entry.POLICIES -> DocumentBudgets(maxPolicies = limit)
        DocumentBudgets.Entry.STATEMENTS -> DocumentBudgets(maxStatements = limit)
        DocumentBudgets.Entry.ROLE_BINDINGS -> DocumentBudgets(maxRoleBindings = limit)
        DocumentBudgets.Entry.ANYONE_BINDINGS -> DocumentBudgets(maxAnyoneBindings = limit)
    }

    /** Writes [document] as the head of [key] directly, as cosec-migrate or a revision older than the budget would. */
    private suspend fun stored(key: SnapshotKey, document: PolicyDocument): Head {
        harness.revisions.publish(key, harness.revisions.head(key), document, StoreFixtures.publication())
        return harness.revisions.head(key)!!
    }

    private suspend fun platformBudgets(budgets: DocumentBudgets) {
        stored(SnapshotKey.Platform, StoreFixtures.candidate(SnapshotKey.Platform).copy(budgets = budgets))
    }

    private suspend fun storedAcme(policies: List<PolicyDefinition>, bindings: List<Binding>): Head =
        stored(ACME, StoreFixtures.candidate(ACME, policies, bindings))

    /** The operations that make acme's head of [from] entries into one of [to] entries of [entry]. */
    private fun operations(entry: DocumentBudgets.Entry, from: Int, to: Int): List<Operation> {
        val (wantedPolicies, wantedBindings) = entries(entry, to)
        val (heldPolicies, heldBindings) = entries(entry, from)
        val wantedPolicyIds = wantedPolicies.map { it.id }.toSet()
        val wantedBindingIds = wantedBindings.map { it.id }.toSet()
        return wantedPolicies.map { Operation.PutPolicy(it) } +
            heldPolicies.filter { it.id !in wantedPolicyIds }.map { Operation.RemovePolicy(it.id) } +
            wantedBindings.map { Operation.PutBinding(it) } +
            heldBindings.filter { it.id !in wantedBindingIds }.map { Operation.RemoveBinding(it.id) }
    }

    private fun budgetFindings(change: ChangeRequest) = change.findings.filter { "CTL-40" in it.message }

    @ParameterizedTest
    @EnumSource(DocumentBudgets.Entry::class)
    fun `a change may not raise a count over its budget, and may keep or shrink one already over`(
        entry: DocumentBudgets.Entry,
    ) = runBlocking<Unit> {
        platformBudgets(budgets(entry, 1))
        val (policies, bindings) = entries(entry, 2)
        val head = storedAcme(policies, bindings).revision

        val raised = changes.propose(ALICE_OP, ACME, head, operations(entry, 2, 3), "raise")
        raised.state.assert().isEqualTo(ChangeState.DRAFT)
        raised.errors.single().let {
            it.check.assert().isEqualTo("V2")
            it.path.assert().isEqualTo(entry.path)
            it.status.assert().isEqualTo(422)
            it.message.assert().contains("would hold 3 ${entry.what}").contains("limits.${entry.member} (1)")
                .contains("more than the 2 it holds now").contains("CTL-40")
        }

        // A change that leaves the count at 2: an entry rewritten, or one this budget does not count added.
        val keeping = when (entry) {
            DocumentBudgets.Entry.POLICIES -> Operation.PutPolicy(allow("e0", action = "order:write"))
            DocumentBudgets.Entry.STATEMENTS -> Operation.PutPolicy(statements("e-s", 2))
            else -> Operation.PutPolicy(allow("other-entry"))
        }
        val kept = changes.propose(ALICE_OP, ACME, head, listOf(keeping), "keep")
        kept.state.assert().describedAs("${kept.findings}").isEqualTo(ChangeState.CHECKED)
        budgetFindings(kept).single().let {
            it.severity.assert().isEqualTo(Severity.WARNING)
            it.path.assert().isEqualTo(entry.path)
            it.message.assert().contains("holds 2 ${entry.what}").contains("limits.${entry.member} (1)")
        }

        val shrunk = changes.propose(ALICE_OP, ACME, head, operations(entry, 2, 1), "shrink")
        shrunk.state.assert().describedAs("${shrunk.findings}").isEqualTo(ChangeState.CHECKED)
        budgetFindings(shrunk).assert().isEmpty()
    }

    @ParameterizedTest
    @EnumSource(DocumentBudgets.Entry::class)
    fun `a new document is held to the limit itself`(entry: DocumentBudgets.Entry) = runBlocking<Unit> {
        platformBudgets(budgets(entry, 1))
        val (one, oneBinding) = entries(entry, 1)
        val within = changes.propose(
            ALICE_OP,
            ACME,
            0,
            one.map { Operation.PutPolicy(it) } + oneBinding.map { Operation.PutBinding(it) },
            "one"
        )
        within.state.assert().describedAs("${within.findings}").isEqualTo(ChangeState.CHECKED)
        budgetFindings(within).assert().isEmpty()
        val (two, twoBindings) = entries(entry, 2)
        val over = changes.propose(
            ALICE_OP,
            ACME,
            0,
            two.map { Operation.PutPolicy(it) } + twoBindings.map { Operation.PutBinding(it) },
            "two"
        )
        over.errors.single().message.assert().contains("more than the 0 it holds now")
    }

    @Test
    fun `a rollback is held to the rule against the current head`() = runBlocking<Unit> {
        platformBudgets(DocumentBudgets(maxPolicies = 1))
        storedAcme((0 until 3).map { allow("p$it") }, emptyList())
        val second = storedAcme(listOf(allow("p0"), allow("p1")), emptyList())
        // Back to three from a head of two: it raises a count over its budget.
        val growing = changes.rollback(PLATO, ACME, 1, "back to three")
        growing.state.assert().isEqualTo(ChangeState.DRAFT)
        growing.errors.single().message.assert().contains("would hold 3 policies").contains("the 2 it holds now")
        // Back to two from a head of three: it shrinks, and is checked with the count still over.
        storedAcme((0 until 3).map { allow("q$it") }, emptyList())
        val shrinking = changes.rollback(PLATO, ACME, second.revision, "back to two")
        shrinking.state.assert().describedAs("${shrinking.findings}").isEqualTo(ChangeState.CHECKED)
        budgetFindings(shrinking).single().severity.assert().isEqualTo(Severity.WARNING)
    }

    @Test
    fun `a budget lowered after a change was checked keeps it from publishing`() = runBlocking<Unit> {
        platformBudgets(DocumentBudgets.DEFAULT)
        val change = changes.propose(
            PLATO,
            ACME,
            0,
            listOf(Operation.PutPolicy(allow("a")), Operation.PutPolicy(allow("b"))),
            "two"
        )
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        changes.skipShadow(PLATO, change.id, "test")
        platformBudgets(DocumentBudgets(maxPolicies = 1))
        val published = changes.publish(PLATO, change.id)
        published.state.assert().isEqualTo(ChangeState.DRAFT)
        published.errors.single().message.assert().contains("CTL-40")
        harness.revisions.head(ACME).assert().isNull()
    }

    @Test
    fun `a client's change carries at most maxChangeOperations operations, an apply is not held to it`() = runBlocking<Unit> {
        platformBudgets(DocumentBudgets(maxChangeOperations = 2))
        val three = (0 until 3).map { Operation.PutPolicy(allow("p$it")) }
        val refused = runCatching { changes.propose(ALICE_OP, ACME, 0, three, "three") }.exceptionOrNull()
        refused.assert().isInstanceOf(ChangeRejectedException::class.java)
        (refused as ChangeRejectedException).status.assert().isEqualTo(400)
        refused.message.assert().contains("holds 3 operations").contains("limits.maxChangeOperations (2)")
            .contains("CTL-41")
        harness.memory.openChanges().assert().isEmpty()
        changes.propose(ALICE_OP, ACME, 0, three.take(2), "two").state.assert().isEqualTo(ChangeState.CHECKED)
        val desired = StoreFixtures.candidate(ACME, (0 until 3).map { allow("p$it") })
        changes.apply(ALICE_OP, ACME, desired, ApplyMode.REPLACE, "apply").state.assert().isEqualTo(ChangeState.CHECKED)
    }
}
