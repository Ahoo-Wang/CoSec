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
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** The change flow of RFC 0007 §6.1, §6.2 and §6.7 case by case. */
class ChangesTest {
    private val harness = ChangeHarness(options = ChangeOptions(maxDocumentBytes = 2_000, shadow = IMMEDIATE_WINDOW))
    private val changes = harness.changes

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(operatorFor(key), key, head, operations.toList(), "test")
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id)
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
        val merge = changes.apply(ALICE_OP, ACME, desired, ApplyMode.MERGE, "merge")
        merge.operations.map { it::class.simpleName }.assert().containsExactly("PutPolicy", "PutPolicy")
        (merge.operations[0] as Operation.PutPolicy).ifMatch.assert().isEqualTo(EntryDigest.of(head.policies[0]))
        (merge.operations[1] as Operation.PutPolicy).ifMatch.assert().isNull()
        changes.abandon(ALICE_OP, merge.id)
        val replace = changes.apply(ALICE_OP, ACME, desired, ApplyMode.REPLACE, "replace")
        replace.operations.map { it::class.simpleName }.assert()
            .containsExactly("PutPolicy", "PutPolicy", "RemovePolicy", "RemoveBinding")
        changes.skipShadow(ALICE_OP, replace.id, "test")
        changes.publish(ALICE_OP, replace.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        harness.revisions.head(ACME)!!.document.let { document ->
            document.policies.map { it.id.value }.assert().containsExactly("a", "c")
            document.bindings.assert().isEmpty()
        }
        // Applying what is there proposes nothing.
        changes.apply(ALICE_OP, ACME, desired, ApplyMode.REPLACE, "again").operations.assert().isEmpty()
    }

    @Test
    fun `a rollback restores an earlier revision as a checked change`() = runBlocking<Unit> {
        publish(ACME, Operation.PutPolicy(allow("old")))
        publish(ACME, Operation.RemovePolicy(PolicyId("old")), Operation.PutPolicy(allow("new")))
        val rollback = changes.rollback(ALICE_OP, ACME, 1, "back")
        rollback.kind.assert().isEqualTo(ProvenanceKind.ROLLBACK)
        rollback.state.assert().isEqualTo(ChangeState.CHECKED)
        changes.skipShadow(ALICE_OP, rollback.id, "test")
        changes.publish(ALICE_OP, rollback.id).publishedRevision.assert().isEqualTo(3)
        harness.revisions.head(ACME)!!.document.let {
            it.policies.map { policy -> policy.id.value }.assert().containsExactly("old")
            it.provenance!!.kind.assert().isEqualTo(ProvenanceKind.ROLLBACK)
        }
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.rollback(ALICE_OP, ACME, 9, "nowhere") } }
            .extracting { (it as ChangeRejectedException).status }.isEqualTo(410)
    }

    @Test
    fun `names, size and shared ids are checked`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("shared")))
        val reserved = changes.propose(
            ALICE_OP,
            ACME,
            0,
            listOf(Operation.PutPolicy(allow("cosec:mine")), Operation.PutBinding(bind("g:x", "cosec:mine"))),
            "reserved"
        )
        reserved.errors.filter { it.check == "V2" }.map { it.path }.assert().containsExactly(
            "operations[0]",
            "operations[1]"
        )
        // Claiming to be a migration exempts nothing: a change is never of kind MIGRATE (review K1).
        runCatching {
            changes.propose(
                ALICE_OP,
                ACME,
                0,
                listOf(Operation.PutPolicy(allow("cosec:mine"))),
                "migrated",
                ProvenanceKind.MIGRATE
            )
        }.exceptionOrNull().let { (it as ChangeRejectedException).status }.assert().isEqualTo(400)
        changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("shared"))), "shadowing")
            .errors.map { it.check to it.path }.assert().containsExactly("V2" to "$.policies[0].id")
        val big = (1..30).map { Operation.PutPolicy(allow("p$it", resource = "order/${"x".repeat(40)}")) }
        changes.propose(
            ALICE_OP,
            ACME,
            0,
            big,
            "big"
        ).errors.map { it.check to it.status }.assert().contains("V2" to 400)
    }

    @Test
    fun `sections belong to their document kinds`() = runBlocking<Unit> {
        val catalog = Catalog(listOf(CatalogGroup("order", permissions = listOf(CatalogPermission("read")))))
        val wrong = changes.propose(
            ALICE_OP,
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
            ALICE_OP,
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

    /** What the approver held is kept, so that publish can check it again (review K3). */
    @Test
    fun `an approval reads back with what its approver held`() = runBlocking<Unit> {
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("a"))), "x")
        val memberships = me.ahoo.cosec.model.Memberships(
            me.ahoo.cosec.model.Memberships.Source.CLAIMS,
            mapOf(
                me.ahoo.cosec.model.SpaceId.DEFAULT to setOf(me.ahoo.cosec.model.RoleId("admin")),
                me.ahoo.cosec.model.SpaceId("team-a") to setOf(me.ahoo.cosec.model.RoleId("ops")),
            ),
            listOf(PolicyId("attached")),
        )
        val approval = Approval(
            PLATO.actor,
            StoreFixtures.START,
            "c".repeat(64),
            listOf("a[0]"),
            memberships,
            mapOf("department" to "ops")
        )
        val read = ChangeRequestCodec.decode(ChangeRequestCodec.encode(change.copy(approval = approval))).approval!!
        read.copy(memberships = null).assert().isEqualTo(approval.copy(memberships = null))
        val held = read.memberships!!
        held.source.assert().isEqualTo(memberships.source)
        held.roles.assert().isEqualTo(memberships.roles)
        held.attachments.assert().isEqualTo(memberships.attachments)
    }

    /** RFC 0007 §7.6: a boundary is set like a section, resolves in the platform, and holds the policy it names. */
    @Test
    fun `boundaries are set, resolved and kept`() = runBlocking<Unit> {
        val fence = PolicyDefinition(PolicyId("fence"), false, null, allow("x").statements, boundary = true)
        publish(
            SnapshotKey.Platform,
            Operation.PutPolicy(fence),
            Operation.PutPolicy(allow("plain")),
            Operation.SetBoundary(PolicyId("fence"))
        )
        harness.revisions.head(SnapshotKey.Platform)!!.document.boundary!!.value.assert().isEqualTo("fence")
        changes.propose(ALICE_OP, ACME, 0, listOf(Operation.SetBoundary(PolicyId("plain"))), "not a boundary")
            .errors.map { it.check to it.path }.assert().containsExactly("REFERENCES" to "$.boundary")
        publish(ACME, Operation.SetBoundary(PolicyId("fence")))
        harness.revisions.head(ACME)!!.document.boundary!!.value.assert().isEqualTo("fence")
        // The tenant names it: the platform can neither remove it nor stop naming it as the default and drop it.
        changes.propose(
            PLATO,
            SnapshotKey.Platform,
            1,
            listOf(Operation.SetBoundary(null), Operation.RemovePolicy(PolicyId("fence"))),
            "drop"
        )
            .errors.map { it.check }.assert().containsExactly("REFERENCES")
        changes.propose(PLATO, SnapshotKey.App("order"), 0, listOf(Operation.SetBoundary(PolicyId("fence"))), "app")
            .errors.map { it.check }.assert().containsExactly("OPERATION")
        // Applying the head proposes nothing; a replace without the boundary removes it.
        val head = harness.revisions.head(ACME)!!.document
        changes.apply(ALICE_OP, ACME, head, ApplyMode.REPLACE, "same").operations.assert().isEmpty()
        val cleared = changes.apply(
            ALICE_OP,
            ACME,
            StoreFixtures.candidate(ACME, emptyList()),
            ApplyMode.REPLACE,
            "clear"
        )
        (cleared.operations.single() as Operation.SetBoundary).boundary.assert().isNull()
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
            Operation.SetBoundary(PolicyId("fence")),
            Operation.SetBoundary(null),
        )
        val change = changes.propose(ALICE_OP, ACME, 0, operations, "all of them")
        val stored = ChangeRequestCodec.encode(change)
        val read = ChangeRequestCodec.decode(stored)
        ChangeRequestCodec.encode(read).assert().isEqualTo(stored)
        read.findings.assert().isEqualTo(change.findings)
        read.state.assert().isEqualTo(change.state)
        val approved = change.copy(
            escalations = listOf(
                Escalation("a[0] order:read on order/{id} to binding x in (order, *)", "NOT_COVERED", "why")
            ),
            approval = Approval(PLATO.actor, StoreFixtures.START, "c".repeat(64), listOf("a[0]")),
        )
        val again = ChangeRequestCodec.decode(ChangeRequestCodec.encode(approved))
        again.escalations.assert().isEqualTo(approved.escalations)
        again.approval.assert().isEqualTo(approved.approval)
        val shadowed = approved.copy(
            shadow = ShadowState(
                "c1@1",
                StoreFixtures.START,
                ShadowSummary(
                    mapOf("NARROWED" to 2L, "SAME" to 0L),
                    StoreFixtures.START,
                    insufficient = true,
                    blocked = false
                )
            ),
        )
        ChangeRequestCodec.decode(ChangeRequestCodec.encode(shadowed)).shadow.assert().isEqualTo(shadowed.shadow)
        val confirmed = change.copy(confirmations = listOf(Confirmation(PolicyId("p"), 2, "d".repeat(64))))
        ChangeRequestCodec.decode(
            ChangeRequestCodec.encode(confirmed)
        ).confirmations.assert().isEqualTo(confirmed.confirmations)
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
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("a"))), "x")
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.publish(ALICE_OP, change.id) } }
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.shadowed(change.id) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { changes.skipShadow(ALICE_OP, change.id, " ") } }
        changes.abandon(ALICE_OP, change.id)
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.abandon(ALICE_OP, change.id) } }
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.get(ChangeId("missing")) } }
            .extracting { (it as ChangeRejectedException).status }.isEqualTo(404)
        assertThrownBy<IllegalArgumentException> { ChangeId("a:b") }
    }

    /** Changes keep a document's rate limit rules (RFC 0006 §8): no operation changes them yet, so nothing drops them. */
    @Test
    fun `publishing a change keeps the document's rate limit rules`() = runBlocking<Unit> {
        val rule = me.ahoo.cosec.engine.flat.RateLimitSource(
            "orders",
            me.ahoo.cosec.engine.flat.StatementSource("", me.ahoo.cosec.model.Effect.ALLOW, listOf("order:read")),
            null,
            me.ahoo.cosec.model.decision.RateLimitSpec(10.0),
        )
        val (kind, owner) = me.ahoo.cosec.control.store.Revisions.ownerOf(ACME)
        val limited = me.ahoo.cosec.snapshot.document.PolicyDocument(
            kind,
            owner,
            1,
            StoreFixtures.START,
            emptyList(),
            emptyList(),
            rateLimits = listOf(rule)
        )
        harness.revisions.publish(ACME, null, limited, StoreFixtures.publication())
        publish(ACME, Operation.PutPolicy(allow("a"))).state.assert().isEqualTo(ChangeState.PUBLISHED)
        harness.revisions.head(ACME)!!.document.rateLimits.map { it.id }.assert().containsExactly("orders")
    }
}
