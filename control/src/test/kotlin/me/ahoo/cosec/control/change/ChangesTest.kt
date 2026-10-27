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
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Catalog
import me.ahoo.cosec.snapshot.document.CatalogGroup
import me.ahoo.cosec.snapshot.document.CatalogPermission
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** The change flow of RFC 0007 §6.1, §6.2 and §6.7 case by case. */
class ChangesTest {
    private val harness = ChangeHarness(options = ChangeOptions(maxDocumentBytes = 2_000))
    private val changes = harness.changes

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(ALICE, key, head, operations.toList(), "test")
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(change.id, "test")
        return changes.publish(change.id)
    }

    @Test
    fun `apply merges or replaces, guarded by what it read`() = runBlocking<Unit> {
        publish(
            ACME,
            Operation.PutPolicy(allow("a")),
            Operation.PutPolicy(allow("b")),
            Operation.PutBinding(bind("x", "a"))
        )
        val head = harness.revisions.head(ACME)!!.document
        val desired = StoreFixtures.candidate(ACME, listOf(allow("a", action = "order:list"), allow("c")))
        val merge = changes.apply(ALICE, ACME, desired, ApplyMode.MERGE, "merge")
        merge.operations.map { it::class.simpleName }.assert().containsExactly("PutPolicy", "PutPolicy")
        (merge.operations[0] as Operation.PutPolicy).ifMatch.assert().isEqualTo(EntryDigest.of(head.policies[0]))
        (merge.operations[1] as Operation.PutPolicy).ifMatch.assert().isNull()
        changes.abandon(merge.id)
        val replace = changes.apply(ALICE, ACME, desired, ApplyMode.REPLACE, "replace")
        replace.operations.map { it::class.simpleName }.assert()
            .containsExactly("PutPolicy", "PutPolicy", "RemovePolicy", "RemoveBinding")
        changes.skipShadow(replace.id, "test")
        changes.publish(replace.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        harness.revisions.head(ACME)!!.document.let { document ->
            document.policies.map { it.id.value }.assert().containsExactly("a", "c")
            document.bindings.assert().isEmpty()
        }
        // Applying what is there proposes nothing.
        changes.apply(ALICE, ACME, desired, ApplyMode.REPLACE, "again").operations.assert().isEmpty()
    }

    @Test
    fun `a rollback restores an earlier revision as a checked change`() = runBlocking<Unit> {
        publish(ACME, Operation.PutPolicy(allow("old")))
        publish(ACME, Operation.RemovePolicy(PolicyId("old")), Operation.PutPolicy(allow("new")))
        val rollback = changes.rollback(ALICE, ACME, 1, "back")
        rollback.kind.assert().isEqualTo(ProvenanceKind.ROLLBACK)
        rollback.state.assert().isEqualTo(ChangeState.CHECKED)
        changes.skipShadow(rollback.id, "test")
        changes.publish(rollback.id).publishedRevision.assert().isEqualTo(3)
        harness.revisions.head(ACME)!!.document.let {
            it.policies.map { policy -> policy.id.value }.assert().containsExactly("old")
            it.provenance!!.kind.assert().isEqualTo(ProvenanceKind.ROLLBACK)
        }
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.rollback(ALICE, ACME, 9, "nowhere") } }
            .extracting { (it as ChangeRejectedException).status }.isEqualTo(410)
    }

    @Test
    fun `names, size and shared ids are checked`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("shared")))
        val reserved = changes.propose(
            ALICE,
            ACME,
            0,
            listOf(Operation.PutPolicy(allow("cosec:mine")), Operation.PutBinding(bind("g:x", "cosec:mine"))),
            "reserved"
        )
        reserved.errors.filter { it.check == "V2" }.map { it.path }.assert().containsExactly(
            "operations[0]",
            "operations[1]"
        )
        changes.propose(
            ALICE,
            ACME,
            0,
            listOf(Operation.PutPolicy(allow("cosec:mine"))),
            "migrated",
            ProvenanceKind.MIGRATE
        )
            .errors.filter { it.check == "V2" }.assert().isEmpty()
        changes.propose(ALICE, ACME, 0, listOf(Operation.PutPolicy(allow("shared"))), "shadowing")
            .errors.map { it.check to it.path }.assert().containsExactly("V2" to "$.policies[0].id")
        val big = (1..30).map { Operation.PutPolicy(allow("p$it", resource = "order/${"x".repeat(40)}")) }
        changes.propose(ALICE, ACME, 0, big, "big").errors.map { it.check to it.status }.assert().contains("V2" to 400)
    }

    @Test
    fun `sections belong to their document kinds`() = runBlocking<Unit> {
        val catalog = Catalog(listOf(CatalogGroup("order", permissions = listOf(CatalogPermission("read")))))
        val wrong = changes.propose(
            ALICE,
            ACME,
            0,
            listOf(
                Operation.PutCatalog(catalog),
                Operation.SetLimits(ConditionLimits()),
                Operation.SetAttributes(emptyList())
            ),
            "wrong",
        )
        wrong.errors.map { it.check to it.path }.assert()
            .containsExactly(
                "OPERATION" to "operations[0]",
                "OPERATION" to "operations[1]",
                "OPERATION" to "operations[2]"
            )
        publish(SnapshotKey.App("order"), Operation.PutCatalog(catalog)).state.assert().isEqualTo(ChangeState.PUBLISHED)
        harness.revisions.head(
            SnapshotKey.App("order")
        )!!.document.catalog!!.groups.single().name.assert().isEqualTo("order")
        val absent = changes.propose(
            ALICE,
            ACME,
            0,
            listOf(Operation.RemovePolicy(PolicyId("nope")), Operation.RemoveBinding(BindingId("nope"))),
            "absent"
        )
        absent.errors.map { it.status }.assert().containsExactly(422, 422)
    }

    @Test
    fun `publishing keeps the platform's limits and attributes`() = runBlocking<Unit> {
        val attributes = listOf(Attribute.external("ext.geo.region", AttributeType.STRING))
        publish(
            SnapshotKey.Platform,
            Operation.SetLimits(ConditionLimits(maxNodes = 32)),
            Operation.SetAttributes(attributes)
        )
        val platform = harness.revisions.head(SnapshotKey.Platform)!!.document
        platform.limits!!.maxNodes.assert().isEqualTo(32)
        platform.attributes!!.map { it.path to it.type }.assert().containsExactly(
            "ext.geo.region" to AttributeType.STRING
        )
    }

    @Test
    fun `a change reads back as it was stored`() = runBlocking<Unit> {
        val operations = listOf(
            Operation.PutPolicy(allow("a", resource = "order/{id}"), "f".repeat(64)),
            Operation.RemovePolicy(PolicyId("b"), "e".repeat(64)),
            Operation.PutBinding(bind("x", "a", app = "order")),
            Operation.RemoveBinding(BindingId("y")),
            Operation.PutCatalog(null),
            Operation.SetLimits(ConditionLimits(maxCost = 100)),
            Operation.SetAttributes(listOf(Attribute.external("ext.geo.region", AttributeType.IP))),
        )
        val change = changes.propose(ALICE, ACME, 0, operations, "all of them")
        val stored = ChangeRequestCodec.encode(change)
        val read = ChangeRequestCodec.decode(stored)
        ChangeRequestCodec.encode(read).assert().isEqualTo(stored)
        read.findings.assert().isEqualTo(change.findings)
        read.state.assert().isEqualTo(change.state)
        val slot = StageSlot(change.id, StoreFixtures.START)
        ChangeRequestCodec.decodeSlot(ChangeRequestCodec.encode(slot)).assert().isEqualTo(slot)
        ChangeRequestCodec.decodeSlot(ChangeRequestCodec.encode(StageSlot.FREE)).assert().isEqualTo(StageSlot.FREE)
        listOf("{}", """{"id":"x"}""", "[]", "not json").forEach { text ->
            assertThrownBy<me.ahoo.cosec.snapshot.SnapshotFormatException> {
                ChangeRequestCodec.decode(
                    text.encodeToByteArray()
                )
            }
        }
    }

    @Test
    fun `transitions only happen from the states that allow them`() = runBlocking<Unit> {
        val change = changes.propose(ALICE, ACME, 0, listOf(Operation.PutPolicy(allow("a"))), "x")
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.publish(change.id) } }
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.shadowed(change.id) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { changes.skipShadow(change.id, " ") } }
        changes.abandon(change.id)
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.abandon(change.id) } }
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.get(ChangeId("missing")) } }
            .extracting { (it as ChangeRejectedException).status }.isEqualTo(404)
        assertThrownBy<IllegalArgumentException> { ChangeId("a:b") }
    }
}
