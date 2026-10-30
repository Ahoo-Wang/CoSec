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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.runtime.document.Catalog
import me.ahoo.cosec.runtime.document.CatalogGroup
import me.ahoo.cosec.runtime.document.CatalogPermission
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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
            assertThrownBy<me.ahoo.cosec.spi.snapshot.SnapshotFormatException> {
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
        val limited = me.ahoo.cosec.runtime.document.PolicyDocument(
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

    @Test
    fun `widening an app permission policy lists the tenant bindings it widens - RC1-34`() = runBlocking<Unit> {
        val order = SnapshotKey.App("order")
        publish(
            order,
            Operation.PutPolicy(allow("perm:order/read", action = "order:read")),
            Operation.PutPolicy(allow("perm:order/write", action = "order:write")),
        )
        publish(
            ACME,
            Operation.PutBinding(bind("readers", "perm:order/read", app = "order")),
            Operation.PutBinding(bind("writers", "perm:order/write", app = "order")),
        )
        // Four-eyes, so that the platform's own change is analyzed as far as an app document is.
        val ids = java.util.concurrent.atomic.AtomicInteger()
        val fourEyes = Changes(
            harness.memory,
            harness.revisions,
            { ChangeId("four-eyes-${ids.incrementAndGet()}") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(fourEyes = true, shadow = IMMEDIATE_WINDOW),
        )
        val widened = fourEyes.propose(
            PLATO,
            order,
            1,
            listOf(Operation.PutPolicy(allow("perm:order/read", action = "order:*"))),
            "widen",
        )
        widened.escalations.assert().containsExactly(
            Escalation("document app:order", "NOT_ANALYZED", "only the trust root publishes it alone"),
            Escalation(
                "binding readers of [tenant:acme]",
                "TENANT_BINDING",
                "[perm:order/read] statements [0] widen what it grants to role editor in space [*]",
            ),
        )
        val narrowed = fourEyes.propose(
            PLATO,
            order,
            1,
            listOf(Operation.PutPolicy(allow("perm:order/read", action = "order:read", resource = "order/1"))),
            "narrow",
        )
        narrowed.escalations.map { it.reason }.assert().containsExactly("NOT_ANALYZED")
    }

    /** Changes over [store] with four-eyes on, so that every operator's change is analyzed as far as it goes. */
    private fun fourEyes(
        store: ControlStore = harness.memory,
        listeners: ChangeListeners = ChangeListeners.NONE
    ): Changes {
        val ids = java.util.concurrent.atomic.AtomicInteger()
        return Changes(
            store,
            Revisions(store, harness.clock),
            { ChangeId("four-eyes-${ids.incrementAndGet()}") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(fourEyes = true, shadow = IMMEDIATE_WINDOW),
            listeners = listeners,
        )
    }

    private fun deny(id: String, action: String) = PolicyDefinition(
        PolicyId(id),
        attachable = false,
        condition = null,
        statements = listOf(StatementSource("", Effect.DENY, listOf(action))),
    )

    private fun binding(id: String, subject: SubjectRef, policy: String, app: String? = null, space: SpaceId? = null) =
        Binding(
            BindingId(id),
            subject,
            BindingScope(
                app?.let { Match.Exactly(AppId(it)) } ?: Match.Any,
                space?.let { Match.Exactly(it) } ?: Match.Any
            ),
            PolicyId(policy),
        )

    @Test
    fun `the analysis reads every app document, and one it cannot read leaves a grant unknown - RC1-4`() = runBlocking<Unit> {
        // billing denies order:delete to the role ops of every tenant; the change below never names billing.
        publish(
            SnapshotKey.App("billing"),
            Operation.PutPolicy(deny("perm:billing/no-delete", "order:delete")),
            Operation.PutBinding(
                binding("no-delete", SubjectRef.Role(null, RoleId("ops")), "perm:billing/no-delete", "billing")
            ),
        )
        publish(
            ACME,
            Operation.PutPolicy(allow("orders", action = "order:*", resource = "order/**")),
            Operation.PutBinding(
                binding("ops", SubjectRef.Role(null, RoleId("ops")), "orders", space = SpaceId.DEFAULT)
            ),
        )
        val ops =
            Operator(
                ALICE,
                Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId.DEFAULT to setOf(RoleId("ops"))), emptyList())
            )
        val unreadable = HashSet<SnapshotKey>()
        val store = object : ControlStore by harness.memory {
            override suspend fun head(key: SnapshotKey): StoredDocument? {
                check(key !in unreadable) { "[$key] cannot be read" }
                return harness.memory.head(key)
            }
        }
        val changes = fourEyes(store)
        val grant =
            listOf(
                Operation.PutBinding(
                    binding(
                        "bob",
                        SubjectRef.Principal(TenantId("acme"), PrincipalId("bob")),
                        "orders",
                        space = SpaceId.DEFAULT
                    )
                )
            )
        changes.propose(ops, ACME, 1, grant, "grant").escalations.map { it.reason }.distinct().assert()
            .containsExactly("DENY_OVERLAP")
        unreadable += SnapshotKey.App("billing")
        changes.propose(ops, ACME, 1, grant, "grant again").escalations.map { it.reason }.distinct().assert()
            .containsExactly("UNREADABLE")
    }

    @Test
    fun `the platform cannot add an id a tenant uses, and one that only defines it never blocks a removal - RC1-9`() =
        runBlocking<Unit> {
            publish(ACME, Operation.PutPolicy(allow("editor")), Operation.PutBinding(bind("staff", "editor")))
            val platform = SnapshotKey.Platform
            val added = changes.propose(
                PLATO,
                platform,
                0,
                listOf(Operation.PutPolicy(allow("editor", action = "order:*"))),
                "add"
            )
            added.errors.map { it.check to it.path }.assert().containsExactly("V2" to "$.policies[0].id")
            added.errors.single().message.assert().contains("tenant:acme")
            changes.propose(
                PLATO,
                platform,
                0,
                listOf(Operation.PutPolicy(allow("other"))),
                "other"
            ).errors.assert().isEmpty()
            // A collision from before the upgrade, written as cosec-migrate writes: acme defines editor itself, so it
            // does not keep the platform from removing its own, which ends the ambiguity.
            harness.revisions.publish(
                platform,
                null,
                StoreFixtures.candidate(platform, listOf(allow("editor"))),
                StoreFixtures.publication()
            )
            changes.propose(PLATO, platform, 1, listOf(Operation.RemovePolicy(PolicyId("editor"))), "remove")
                .errors.assert().isEmpty()
            // A tenant that binds the platform's editor does.
            val beta = SnapshotKey.Tenant("beta")
            harness.revisions.publish(
                beta,
                null,
                StoreFixtures.candidate(beta, bindings = listOf(bind("staff", "editor"))),
                StoreFixtures.publication()
            )
            changes.propose(PLATO, platform, 1, listOf(Operation.RemovePolicy(PolicyId("editor"))), "remove")
                .errors.map {
                    it.check to it.message
                }.assert().containsExactly("REFERENCES" to "[editor] is referred to by [tenant:beta]")
        }

    /** RC1-33: what the publish of [grant] by alice records when [meanwhile] lands on the platform just before its write. */
    private suspend fun publishInterleaved(meanwhile: List<PolicyDefinition>?): List<ChangeEvent> {
        val alice = SubjectRef.Principal(TenantId("acme"), PrincipalId("alice"))
        publish(
            SnapshotKey.Platform,
            Operation.PutPolicy(allow("orders", action = "order:*", resource = "order/**")),
            Operation.PutBinding(binding("alice", alice, "orders")),
        )
        val inner = Revisions(harness.memory, harness.clock)
        var pending = meanwhile
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: SnapshotDocument
            ): HeadWrite {
                val platform = pending
                if (key == ACME && platform != null) {
                    pending = null
                    val head = inner.head(SnapshotKey.Platform)!!
                    val candidate = StoreFixtures.candidate(
                        SnapshotKey.Platform,
                        head.document.policies + platform,
                        head.document.bindings + platform.filter { it.statements.single().effect == Effect.DENY }
                            .map { binding(it.id.value, alice, it.id.value) },
                    )
                    inner.publish(SnapshotKey.Platform, head, candidate, StoreFixtures.publication("meanwhile"))
                        .assert().isInstanceOf(PublishOutcome.Published::class.java)
                }
                return harness.memory.putHead(key, expected, document)
            }
        }
        val events = java.util.Collections.synchronizedList(ArrayList<ChangeEvent>())
        val listener = object : ChangeListener {
            override val name = "events"

            override suspend fun onChange(event: ChangeEvent) {
                events += event
            }
        }
        val changes = fourEyes(store, ChangeListeners(listOf(listener)))
        val grant = Operation.PutBinding(
            binding("bob", SubjectRef.Principal(TenantId("acme"), PrincipalId("bob")), "orders")
        )
        val change = changes.propose(ALICE_OP, ACME, 0, listOf(grant), "grant")
        change.escalations.assert().isEmpty()
        changes.skipShadow(ALICE_OP, change.id, "test")
        changes.publish(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        withTimeout(5_000) {
            while (events.none { it.kind == ChangeEvent.Kind.PUBLISHED }) delay(10)
        }
        delay(200)
        return events.filter { it.kind == ChangeEvent.Kind.HEADS_MOVED || it.kind == ChangeEvent.Kind.REVIEW_NEEDED }
    }

    @Test
    fun `a restriction published during a grant's publish is detected, not rolled back - RC1-33`() = runBlocking<Unit> {
        val detected = publishInterleaved(listOf(deny("no-delete", "order:delete"))).single()
        detected.kind.assert().isEqualTo(ChangeEvent.Kind.REVIEW_NEEDED)
        detected.moved.map { it.document }.assert().containsExactly(SnapshotKey.Platform)
        detected.escalations.map { it.reason }.distinct().assert().containsExactly("DENY_OVERLAP")
        harness.revisions.head(ACME)!!.document.bindings.map { it.id.value }.assert().containsExactly("bob")
    }

    @Test
    fun `an unrelated platform publish meanwhile is recorded as nothing new - RC1-33`() = runBlocking<Unit> {
        val detected = publishInterleaved(listOf(allow("unrelated", action = "user:read"))).single()
        detected.kind.assert().isEqualTo(ChangeEvent.Kind.HEADS_MOVED)
        detected.escalations.assert().isEmpty()
    }

    @Test
    fun `heads that did not move are not reported - RC1-33`() = runBlocking<Unit> {
        publishInterleaved(null).assert().isEmpty()
    }
}
