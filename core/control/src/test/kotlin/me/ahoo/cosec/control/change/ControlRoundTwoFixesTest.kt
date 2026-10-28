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
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.DocumentConfirmation
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.shadow.InMemoryShadowReports
import me.ahoo.cosec.runtime.shadow.InstanceStatus
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowReport
import me.ahoo.cosec.runtime.shadow.ShadowReportReader
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The round-2 review of the control plane (RFC 0007): a window sums one report per instance (R1), an approval keeps
 * every attribute of its approver (R2), a lookup that cannot be decided is a 503 (R6), and the break-glass rules hold
 * for the policy a break-glass binding grants and for rollbacks (R4, R5).
 */
class ControlRoundTwoFixesTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private var storeDown = false
    private val store: ControlStore = object : ControlStore by memory {
        override suspend fun change(id: ChangeId) = if (storeDown) error("the store is down") else memory.change(id)
    }
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()
    private val opened = ArrayList<ChangeListeners>()

    private class Audit : ChangeListener {
        override val name: String = "audit"
        override val delivery: Delivery = Delivery.REQUIRED
        val events = java.util.concurrent.CopyOnWriteArrayList<ChangeEvent>()

        override suspend fun onChange(event: ChangeEvent) {
            events += event
        }
    }

    private val audit = Audit()

    private fun changes(
        listeners: List<ChangeListener> = listOf(audit),
        reports: ShadowReportReader = InMemoryShadowReports(clock),
        shadow: ShadowOptions = IMMEDIATE_WINDOW,
    ): Changes {
        val registered = ChangeListeners(listeners).also { opened += it }
        return Changes(
            store,
            revisions,
            { ChangeId("c${ids.incrementAndGet()}") },
            engine.authorizer,
            clock,
            ChangeOptions(shadow = shadow),
            reports = reports,
            listeners = registered,
        )
    }

    private val changes = changes()

    /** walt reads, writes, grants and publishes everywhere, and holds neither cosec:breakglass nor cosec:approve. */
    private val walt = engine.operator(TenantId.PLATFORM, "walt")

    /** sam writes, grants and publishes everywhere, but reads nothing. */
    private val sam = engine.operator(TenantId.PLATFORM, "sam")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")
    private val alice = engine.operator(TenantId("acme"), "alice")

    private fun policy(id: String, actions: List<String>, condition: Condition? = null) = PolicyDefinition(
        PolicyId(id),
        false,
        null,
        listOf(
            StatementSource(
                "",
                Effect.ALLOW,
                actions,
                listOf(ResourceSource("doc/**"), ResourceSource("scope/**")),
                condition
            )
        ),
    )

    private val writer = policy("writer", listOf("cosec:read", "cosec:write", "cosec:grant", "cosec:publish"))
    private val scribe = policy("scribe", listOf("cosec:write", "cosec:grant", "cosec:publish"))
    private val emergency = policy("emergency", listOf("order:read"))

    private fun control(principal: String, policy: PolicyId) = Binding(
        BindingId(principal),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(principal)),
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    private val bindings = listOf(
        control("walt", PolicyId("writer")),
        control("sam", PolicyId("scribe")),
        control("pia", BuiltinPolicies.PLATFORM_ADMIN),
    )

    private fun glass(policy: PolicyId = PolicyId("emergency")) = Binding(
        BindingId("glass"),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("rita")),
        BindingScope(Match.Any, Match.Any),
        policy,
        clock.now.plus(Duration.ofHours(1)).toEpochMilli(),
        true,
    )

    private val policies = listOf(writer, scribe, emergency)

    init {
        runBlocking { publishPlatform(policies, bindings, ProvenanceKind.BOOTSTRAP) }
    }

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    private suspend fun publishPlatform(
        policies: List<PolicyDefinition>,
        bindings: List<Binding>,
        kind: ProvenanceKind = ProvenanceKind.PUBLISH,
    ) {
        revisions.publish(
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform),
            StoreFixtures.candidate(SnapshotKey.Platform, policies, bindings),
            Publication("fixture", kind, pia.actor),
        )
    }

    private suspend fun platformHead(): Long = revisions.head(SnapshotKey.Platform)!!.revision

    private suspend fun refused(block: suspend () -> Any): ChangeRejectedException = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e
    }

    private fun report(
        instance: String,
        narrowed: Long,
        window: String = "c1@1",
        errors: Long = 0,
    ) = ShadowReport(
        window,
        instance,
        mapOf(ShadowOutcome.SAME to 1L, ShadowOutcome.NARROWED to narrowed, ShadowOutcome.CANDIDATE_ERROR to errors),
        emptyMap(),
        emptySet(),
        clock.now,
        clock.now,
    )

    @Test
    fun `a window sums one report per instance, and anything else keeps it open`() = runBlocking<Unit> {
        val options = ShadowOptions(Duration.ZERO, 0, Duration.ofHours(1))
        val state = ShadowState("c1@1", clock.now)
        // Each instance's report is cumulative: its latest replaces its earlier one, never another instance's.
        val reports = InMemoryShadowReports(clock)
        reports.write(report("i1", 2))
        reports.write(report("i2", 1))
        reports.write(report("i1", 3))
        ShadowWindows(reports, options).close(state, clock.now)!!.counts.getValue("NARROWED").assert().isEqualTo(4L)
        // A reader that hands back two reports of one instance, or one of another window, is not summed.
        listOf(
            listOf(report("i1", 2), report("i1", 3)),
            listOf(report("i1", 2), report("i2", 1, window = "c2@1")),
            // A negative count would cancel another instance's: pod-b's errors, here.
            listOf(report("pod-a", 0, errors = -1), report("pod-b", 0, errors = 1)),
        ).forEach { read ->
            val reader = object : ShadowReportReader {
                override suspend fun reports(window: String): List<ShadowReport> = read

                override suspend fun statuses(): List<InstanceStatus> = emptyList()
            }
            assertThrownBy<IllegalStateException> {
                runBlocking {
                    ShadowWindows(
                        reader,
                        options
                    ).close(state, clock.now)
                }
            }
        }
        // One instance's error blocks the window by itself.
        val blocked = InMemoryShadowReports(clock)
        blocked.write(report("pod-a", 0))
        blocked.write(report("pod-b", 0, errors = 1))
        ShadowWindows(blocked, options).close(state, clock.now)!!.blocked.assert().isTrue()
    }

    @Test
    fun `an instance seen reporting on a window must keep reporting until it closes`() = runBlocking<Unit> {
        val written = InMemoryShadowReports(clock)
        val vanished = HashSet<String>()
        val reader = object : ShadowReportReader {
            override suspend fun reports(window: String) = written.reports(window).filter { it.instance !in vanished }

            override suspend fun statuses(): List<InstanceStatus> = emptyList()
        }
        val changes = changes(reports = reader, shadow = ShadowOptions(Duration.ZERO, 10, Duration.ofHours(1)))
        val change = changes.propose(
            pia,
            SnapshotKey.Platform,
            1,
            listOf(Operation.PutPolicy(writer.copyAs("more"))),
            "x"
        )
        val window = changes.stage(pia, change.id).shadow!!.window
        written.write(report("pod-a", 0, window))
        written.write(report("pod-b", 0, window))
        // Not enough samples yet: the window stays open, and the control plane keeps who has reported.
        changes.shadowed(change.id).shadow!!.reporters.assert().isEqualTo(setOf("pod-a", "pod-b"))
        // pod-b vanishes from the window's reporters: enough samples or not, the window does not close without it.
        written.write(report("pod-a", 20, window))
        vanished += "pod-b"
        refused { changes.shadowed(change.id) }.status.assert().isEqualTo(503)
        changes.get(change.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        // Back, with the error it saw: the window closes blocked.
        vanished.clear()
        written.write(report("pod-b", 0, window, errors = 1))
        changes.shadowed(change.id).shadow!!.closed!!.blocked.assert().isTrue()
    }

    @Test
    fun `an approval keeps every attribute of its approver, so that publishing checks what it was decided on`() = runBlocking<Unit> {
        // The approver's right reads a number and a boolean of their token.
        val onCall = Condition.And(
            listOf(
                Condition.Eq("subject.attributes.level", Literal.Str("3")),
                Condition.Eq("subject.attributes.oncall", Literal.Str("true")),
            )
        )
        val approver = policy("approver", listOf("cosec:approve", "cosec:read"), onCall)
        publishPlatform(policies + approver, bindings + control("ada", PolicyId("approver")))
        val attributes = mapOf("level" to 3, "oncall" to true, "teams" to listOf("red", "blue"))
        val ada = engine.operator(TenantId.PLATFORM, "ada", attributes = attributes)
        val app = SnapshotKey.App("order")
        val change = changes.propose(walt, app, 0, listOf(Operation.PutPolicy(writer.copyAs("perm:order/read"))), "x")
        changes.stage(walt, change.id)
        changes.shadowed(change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        val approved = changes.approve(ada, change.id, changes.get(change.id).candidate!!)
        approved.approval!!.attributes.assert().isEqualTo(attributes)
        // As stored and read back, each attribute is what the engine reads: the same text.
        val stored = ChangeRequestCodec.decode(ChangeRequestCodec.encode(approved)).approval!!.attributes
        stored.mapValues { it.value.toString() }.assert().isEqualTo(attributes.mapValues { it.value.toString() })
        changes.publish(walt, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `an approval's other attribute types survive the store`() {
        val attributes = mapOf<String, Any>(
            "ratio" to 0.1f,
            "big" to Long.MAX_VALUE,
            "nested" to mapOf("k" to 1, "flags" to listOf(true, false)),
            "when" to java.time.Instant.parse("2026-11-01T00:00:00Z"),
            "short" to 7.toShort(),
            "byte" to 1.toByte(),
            "double" to 2.5,
            "huge" to java.math.BigInteger("123456789012345678901234567890"),
            "decimal" to java.math.BigDecimal("1.25"),
            "sparse" to listOf("a", null),
        )
        val change = runBlocking {
            changes.propose(walt, SnapshotKey.Platform, 1, listOf(Operation.PutPolicy(writer.copyAs("more"))), "x")
        }
        val approval = Approval(pia.actor, clock.now, change.candidate!!, emptyList(), pia.memberships, attributes)
        val stored = ChangeRequestCodec.decode(ChangeRequestCodec.encode(change.copy(approval = approval))).approval!!
        stored.attributes.mapValues { it.value.toString() }.assert().isEqualTo(
            attributes.mapValues { it.value.toString() }
        )
        // An array is stored as the list of its elements.
        val array = approval.copy(attributes = mapOf("teams" to arrayOf("red", "blue")))
        ChangeRequestCodec.decode(ChangeRequestCodec.encode(change.copy(approval = array))).approval!!.attributes
            .assert().isEqualTo(mapOf("teams" to listOf("red", "blue")))
    }

    @Test
    fun `an approver is checked again only as recorded, and anything else does not count`() = runBlocking<Unit> {
        val change = changes.propose(
            walt,
            SnapshotKey.Platform,
            1,
            listOf(Operation.PutPolicy(writer.copyAs("x"))),
            "x"
        )
        val needed = listOf(Escalation("document platform", "NOT_ANALYZED"))
        val approved = change.copy(
            approval = Approval(pia.actor, clock.now, change.candidate!!, needed.map { it.subject }, pia.memberships)
        )
        ChangeAuthority(engine.authorizer).approverProblem(approved, needed, walt).assert().isNull()
        val noLonger = "the approver no longer holds cosec:approve on the document"
        // An authorizer that cannot check a recorded principal, or fails to, does not count it.
        ChangeAuthority(PERMIT_ALL).approverProblem(approved, needed, walt).assert().isEqualTo(noLonger)
        val failing = object : me.ahoo.cosec.control.authz.ControlAuthorizer {
            override suspend fun decide(
                operator: Operator,
                action: me.ahoo.cosec.model.Action,
                resource: me.ahoo.cosec.model.Resource
            ) =
                me.ahoo.cosec.control.authz.ControlDecision.PERMIT

            override suspend fun decideAs(
                operator: Operator,
                principal: me.ahoo.cosec.spi.authn.AuthenticatedPrincipal,
                memberships: me.ahoo.cosec.model.Memberships,
                action: me.ahoo.cosec.model.Action,
                resource: me.ahoo.cosec.model.Resource,
            ): me.ahoo.cosec.control.authz.ControlDecision = error("down")
        }
        ChangeAuthority(failing).approverProblem(approved, needed, walt).assert().isEqualTo(noLonger)
        // An approver the hard rule keeps from the document never counts, whatever its policies say.
        val elsewhere = approved.copy(
            approval = approved.approval!!.copy(approver = alice.actor, memberships = alice.memberships)
        )
        ChangeAuthority(PERMIT_ALL).approverProblem(elsewhere, needed, walt).assert().isEqualTo(noLonger)
        // The pipeline's authorizer: no request, no decision; documents it cannot confirm, no decision either.
        val principal = me.ahoo.cosec.spi.authn.AuthenticatedPrincipal(
            pia.actor.principal,
            pia.actor.tenant,
            me.ahoo.cosec.spi.authn.MembershipSource.Provider,
        )
        val approve = me.ahoo.cosec.control.authz.ControlActions.APPROVE
        val document = me.ahoo.cosec.control.authz.ControlResources.document(SnapshotKey.Platform)
        engine.authorizer.decideAs(
            Operator(walt.actor, walt.memberships),
            principal,
            pia.memberships,
            approve,
            document
        )
            .assert().isEqualTo(me.ahoo.cosec.control.authz.ControlDecision.DENY)
        engine.confirmation = DocumentConfirmation { error("the snapshot source is down") }
        engine.authorizer.decideAs(walt, principal, pia.memberships, approve, document)
            .assert().isEqualTo(me.ahoo.cosec.control.authz.ControlDecision.INDETERMINATE)
    }

    @Test
    fun `a change whose readability cannot be decided is a 503 at every step, whether or not it exists`() = runBlocking<Unit> {
        val change = changes.propose(
            pia,
            SnapshotKey.Platform,
            1,
            listOf(Operation.PutPolicy(writer.copyAs("more"))),
            "x"
        )
        val missing = ChangeId("c999")
        val steps: List<Pair<String, suspend (Operator, ChangeId) -> Any>> = listOf(
            "get" to { op, id -> changes.visible(op, id) },
            "approve" to { op, id -> changes.approve(op, id, "x") },
            "publish" to { op, id -> changes.publish(op, id) },
            "abandon" to { op, id -> changes.abandon(op, id) },
        )
        engine.confirmation = DocumentConfirmation { error("the snapshot source is down") }
        for ((name, step) in steps) {
            for (operator in listOf(walt, alice)) {
                for (id in listOf(change.id, missing)) {
                    refused { step(operator, id) }.status.assert().describedAs("$name $operator $id").isEqualTo(503)
                }
            }
        }
        // The change's author reads it without a decision.
        changes.visible(pia, change.id).id.assert().isEqualTo(change.id)
        // A store that cannot be read is not "no change" either.
        engine.confirmation = DocumentConfirmation.CURRENT
        storeDown = true
        refused { changes.visible(walt, change.id) }.status.assert().isEqualTo(503)
        storeDown = false
        // Once it can be decided, a change one may not read is the same 404 as one that does not exist (K2).
        for (id in listOf(change.id, missing)) {
            refused { changes.visible(alice, id) }.status.assert().isEqualTo(404)
        }
        refused { changes.visible(walt, missing) }.status.assert().isEqualTo(404)
        changes.visible(walt, change.id).id.assert().isEqualTo(change.id)

        // Over HTTP, the problem is `unavailable`, with no detail.
        val http = ControlHttp(DefaultControlPlane(changes, revisions, ChangeAuthority(engine.authorizer)))
        val security = engine.security(TenantId.PLATFORM, "walt")
        engine.confirmation = DocumentConfirmation { error("the snapshot source is down") }
        http.handle(ControlHttpRequest("GET", "/changes/${change.id.value}"), security).status.assert().isEqualTo(503)
        http.handle(ControlHttpRequest("GET", "/changes/${missing.value}"), security).status.assert().isEqualTo(503)
        engine.confirmation = DocumentConfirmation.CURRENT
        http.handle(ControlHttpRequest("GET", "/changes/${missing.value}"), security).status.assert().isEqualTo(404)
    }

    @Test
    fun `the policy a live break-glass binding grants changes only once the binding has gone`() = runBlocking<Unit> {
        publishPlatform(policies, bindings + glass(), ProvenanceKind.BREAK_GLASS)
        val wider = Operation.PutPolicy(policy("emergency", listOf("*")))
        // Changing it is a break-glass change: cosec:breakglass and a REQUIRED audit first.
        refused { changes.propose(walt, SnapshotKey.Platform, platformHead(), listOf(wider), "x") }
            .status.assert().isEqualTo(403)
        refused { changes(emptyList()).propose(pia, SnapshotKey.Platform, platformHead(), listOf(wider), "x") }
            .status.assert().isEqualTo(503)
        // And while the binding lives, it never checks clean, whoever proposes it.
        val live = changes.propose(pia, SnapshotKey.Platform, platformHead(), listOf(wider), "x")
        live.state.assert().isEqualTo(ChangeState.DRAFT)
        live.errors.map { it.message }.assert().contains(
            "[emergency] is granted by a live break-glass binding: revoke it before changing the policy"
        )
        // Another policy is not held back; once the binding has expired, neither is this one.
        changes.propose(
            walt,
            SnapshotKey.Platform,
            platformHead(),
            listOf(Operation.PutPolicy(writer.copyAs("more"))),
            "x"
        )
            .state.assert().isEqualTo(ChangeState.CHECKED)
        clock.now = clock.now.plus(Duration.ofHours(2))
        changes.propose(walt, SnapshotKey.Platform, platformHead(), listOf(wider), "x").state.assert()
            .isEqualTo(ChangeState.CHECKED)
    }

    @Test
    fun `a rollback is held to every break-glass rule`() = runBlocking<Unit> {
        // Revision 2 grants rita break-glass; revision 3 has revoked it.
        publishPlatform(policies, bindings + glass(), ProvenanceKind.BREAK_GLASS)
        publishPlatform(policies, bindings)
        // Bringing the binding back needs cosec:breakglass and a REQUIRED audit, and is never a break-glass request.
        refused { changes.rollback(walt, SnapshotKey.Platform, 2, "back") }.status.assert().isEqualTo(403)
        refused { changes(emptyList()).rollback(pia, SnapshotKey.Platform, 2, "back") }.status.assert().isEqualTo(503)
        val back = changes.rollback(pia, SnapshotKey.Platform, 2, "back")
        back.state.assert().isEqualTo(ChangeState.DRAFT)
        back.errors.map { it.message }.assert().contains(
            "a break-glass binding is created only by a break-glass request"
        )
        audit.events.filter { it.change == back.id }.map { it.kind }.assert().contains(
            ChangeEvent.Kind.BREAK_GLASS_REQUESTED
        )
        // Once it has expired, its lifetime is wrong as well.
        clock.now = clock.now.plus(Duration.ofHours(2))
        changes.rollback(pia, SnapshotKey.Platform, 2, "back").errors.map { it.message }.assert()
            .anyMatch { it.startsWith("a break-glass binding expires after now") }
        // Restoring an older body of the policy a live binding grants is changing it.
        clock.now = StoreFixtures.START
        publishPlatform(
            policies.map { if (it.id.value == "emergency") policy("emergency", listOf("order:write")) else it },
            bindings
        )
        val older = platformHead()
        publishPlatform(policies, bindings + glass(), ProvenanceKind.BREAK_GLASS)
        refused { changes.rollback(walt, SnapshotKey.Platform, older, "back") }.status.assert().isEqualTo(403)
        // Removing break-glass only narrows: a rollback to before it needs no cosec:breakglass, and no window.
        val before = changes.rollback(walt, SnapshotKey.Platform, 3, "revoke")
        before.state.assert().isEqualTo(ChangeState.CHECKED)
        changes.stage(walt, before.id).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(walt, before.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        revisions.head(SnapshotKey.Platform)!!.document.bindings.none { it.breakGlass }.assert().isTrue()
    }

    @Test
    fun `a rollback needs cosec read before it says which revisions exist`() = runBlocking<Unit> {
        refused { changes.rollback(sam, SnapshotKey.Platform, 1, "back") }.status.assert().isEqualTo(403)
        refused { changes.rollback(sam, SnapshotKey.Platform, 99, "back") }.status.assert().isEqualTo(403)
        refused { changes.rollback(walt, SnapshotKey.Platform, 99, "back") }.status.assert().isEqualTo(410)
    }

    private fun PolicyDefinition.copyAs(id: String) = PolicyDefinition(PolicyId(id), attachable, condition, statements)
}
