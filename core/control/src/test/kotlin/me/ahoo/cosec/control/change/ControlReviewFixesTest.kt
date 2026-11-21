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
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
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
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The round-1 review of the control plane (RFC 0007): a change's kind is its entry point's (K1), a change another
 * tenant may not read is a 404 at every step (K2), and an approval counts only while its approver still may (K3).
 */
class ControlReviewFixesTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()
    private val opened = ArrayList<ChangeListeners>()

    private class Audit : ChangeListener {
        override val name: String = "audit"
        override val delivery: Delivery = Delivery.REQUIRED
        val events = CopyOnWriteArrayList<ChangeEvent>()

        override suspend fun onChange(event: ChangeEvent) {
            events += event
        }
    }

    private fun changes(listeners: List<ChangeListener>): Changes {
        val registered = ChangeListeners(listeners).also { opened += it }
        return Changes(
            store,
            revisions,
            { ChangeId("c${ids.incrementAndGet()}") },
            engine.authorizer,
            clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
            listeners = registered,
            revocations = RevocationReader.NONE,
            lockout = LockoutCheck.UNCHECKED,
        )
    }

    private val audit = Audit()
    private val changes = changes(listOf(audit))

    /** walt writes, grants and publishes everywhere and holds neither cosec:breakglass nor cosec:approve. */
    private val walt = engine.operator(TenantId.PLATFORM, "walt")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")
    private val alice = engine.operator(TenantId("acme"), "alice")

    private val writer = PolicyDefinition(
        PolicyId("writer"),
        false,
        null,
        listOf(
            StatementSource(
                "",
                Effect.ALLOW,
                listOf("cosec:read", "cosec:write", "cosec:grant", "cosec:publish"),
                listOf(ResourceSource("doc/**"), ResourceSource("scope/**")),
            ),
        ),
    )

    private val bindings = listOf(
        control("walt", PolicyId("writer")),
        control("pia", BuiltinPolicies.PLATFORM_ADMIN),
    )

    init {
        runBlocking {
            revisions.publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(SnapshotKey.Platform, listOf(writer), bindings),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, pia.actor),
            )
        }
    }

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    private fun control(principal: String, policy: PolicyId) = Binding(
        BindingId(principal),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(principal)),
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    private fun glass(id: String = "glass", breakGlass: Boolean = true) = Binding(
        BindingId(id),
        // Not walt's: a check that only break-glass permits is refused (review A1), which would hide what is tested here.
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("rita")),
        BindingScope(Match.Any, Match.Any),
        BuiltinPolicies.ROOT,
        clock.now.plus(Duration.ofHours(1)).toEpochMilli(),
        breakGlass,
    )

    private suspend fun refused(block: suspend () -> Any): ChangeRejectedException = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e
    }

    private suspend fun platformHead(): Long = revisions.head(SnapshotKey.Platform)!!.revision

    @Test
    fun `a change's kind is never its input's`() = runBlocking<Unit> {
        val put = listOf(Operation.PutBinding(glass()))
        for (kind in listOf(
            ProvenanceKind.MIGRATE,
            ProvenanceKind.BOOTSTRAP,
            ProvenanceKind.EXPIRE,
            ProvenanceKind.ROLLBACK
        )) {
            refused {
                changes.propose(pia, SnapshotKey.Platform, 1, put, "x", kind)
            }.status.assert().describedAs("$kind").isEqualTo(400)
        }
        store.change(ChangeId("c1")).assert().isNull()
    }

    @Test
    fun `creating or altering break-glass needs cosec breakglass and a REQUIRED audit, whatever the kind`() = runBlocking<Unit> {
        // The review's probe: an ordinary change by an operator without cosec:breakglass is refused before it is recorded.
        refused { changes.propose(walt, SnapshotKey.Platform, 1, listOf(Operation.PutBinding(glass())), "x") }
            .status.assert().isEqualTo(403)
        // Without a REQUIRED change listener, nobody creates one, whatever the change says it is.
        val unaudited = changes(emptyList())
        refused { unaudited.propose(pia, SnapshotKey.Platform, 1, listOf(Operation.PutBinding(glass())), "x") }
            .status.assert().isEqualTo(503)
        // With one, an ordinary change still never checks clean, and its request is recorded first.
        val ordinary = changes.propose(pia, SnapshotKey.Platform, 1, listOf(Operation.PutBinding(glass())), "x")
        ordinary.state.assert().isEqualTo(ChangeState.DRAFT)
        ordinary.errors.map { it.check }.assert().contains("V5")
        audit.events.map { it.kind }.assert().contains(ChangeEvent.Kind.BREAK_GLASS_REQUESTED)

        // Putting an ordinary binding over an existing break-glass binding is altering it, under the same rules.
        revisions.publish(
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform),
            StoreFixtures.candidate(SnapshotKey.Platform, listOf(writer), bindings + glass()),
            Publication("granted", ProvenanceKind.BREAK_GLASS, pia.actor),
        )
        val permanent = listOf(Operation.PutBinding(glass(breakGlass = false)))
        refused {
            changes.propose(walt, SnapshotKey.Platform, platformHead(), permanent, "keep it")
        }.status.assert().isEqualTo(403)
        val altered = changes.propose(pia, SnapshotKey.Platform, platformHead(), permanent, "keep it")
        altered.state.assert().isEqualTo(ChangeState.DRAFT)
        altered.errors.map { it.message }.assert().contains(
            "a break-glass binding is changed only by a break-glass request"
        )
    }

    @Test
    fun `a change another tenant may not read is the same 404 at every step, before its state`() = runBlocking<Unit> {
        unreadableAtEveryStep(alice)
    }

    /**
     * `CTL-15` within one tenant: the hard rule lets nobody, a platform principal bound to no policy, act on the
     * platform document, and only the read decision keeps walt's change from them (a guard that only another tenant's
     * caller exercised let a mutant that skips that decision survive).
     */
    @Test
    fun `a change of its own tenant a caller may not read is the same 404 at every step, before its state`() =
        runBlocking<Unit> {
            unreadableAtEveryStep(engine.operator(TenantId.PLATFORM, "nobody"))
        }

    /** Every step [caller] takes on walt's change answers as for a change that does not exist, and moves nothing. */
    private suspend fun unreadableAtEveryStep(caller: Operator) {
        val change = changes.propose(
            walt,
            SnapshotKey.Platform,
            1,
            listOf(Operation.PutPolicy(writer.copyAs("more"))),
            "x"
        )
        val missing = ChangeId("c999")
        val steps: List<Pair<String, suspend (ChangeId) -> Any>> = listOf(
            "get" to { id -> changes.visible(caller, id) },
            "approve" to { id -> changes.approve(caller, id, "x") },
            "reject" to { id -> changes.reject(caller, id, "x") },
            "publish" to { id -> changes.publish(caller, id) },
            "stage" to { id -> changes.startShadow(caller, id) },
            "skipShadow" to { id -> changes.skipShadow(caller, id, "x") },
            "rebase" to { id -> changes.rebase(caller, id) },
            "abandon" to { id -> changes.abandon(caller, id) },
        )
        for ((name, step) in steps) {
            val theirs = refused { step(change.id) }
            val none = refused { step(missing) }
            (theirs.status to theirs.message).assert().describedAs(
                name
            ).isEqualTo(404 to "no change ${change.id.value}")
            (none.status to none.message).assert().describedAs(name).isEqualTo(404 to "no change ${missing.value}")
        }
        // Nothing moved: the change is as walt left it.
        changes.get(change.id).state.assert().isEqualTo(change.state)
    }

    @Test
    fun `an approval counts only while its approver still holds cosec approve`() = runBlocking<Unit> {
        val app = SnapshotKey.App("order")
        // walt is not the trust root of the app document: what he puts there waits for a second person.
        val change = changes.propose(walt, app, 0, listOf(Operation.PutPolicy(writer.copyAs("perm:order/read"))), "x")
        change.state.assert().describedAs("${change.errors}").isEqualTo(ChangeState.CHECKED)
        changes.startShadow(walt, change.id)
        changes.shadowed(change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        changes.approve(pia, change.id, changes.get(change.id).candidate!!).state.assert().isEqualTo(ChangeState.READY)
        // pia loses platform-admin; the app document's base does not move.
        revisions.publish(
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform),
            StoreFixtures.candidate(SnapshotKey.Platform, listOf(writer), bindings.filterNot { it.id.value == "pia" }),
            Publication("revoke", ProvenanceKind.PUBLISH, walt.actor),
        )
        val outcome = changes.publish(walt, change.id)
        outcome.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        outcome.findings.map { it.message }.assert().contains(
            "the approver no longer holds cosec:approve on the document"
        )
        revisions.head(app).assert().isNull()
    }

    @Test
    fun `an approval that cannot be checked again does not count`() = runBlocking<Unit> {
        val authority = ChangeAuthority(engine.authorizer, revocations = RevocationReader.NONE)
        val change = changes.propose(
            walt,
            SnapshotKey.Platform,
            1,
            listOf(Operation.PutPolicy(writer.copyAs("x"))),
            "x"
        )
        val needed = listOf(Escalation("document platform", Escalation.Reason.NOT_ANALYZED))
        authority.approvals.approverProblem(change, emptyList(), walt).assert().isNull()
        authority.approvals.approverProblem(change, needed, walt).assert().isEqualTo("it needs an approval")
        val unrecorded = change.copy(
            approval = Approval(pia.actor, clock.now, change.candidate!!, listOf("document platform"))
        )
        authority.approvals.approverProblem(
            unrecorded,
            needed,
            walt
        ).assert().isEqualTo("the approval does not say what its approver held")
        val credentialless = unrecorded.copy(approval = unrecorded.approval!!.copy(memberships = pia.memberships))
        // Review item 4: an approval that names no credential is one from before the upgrade, or an older instance's.
        authority.approvals.approverProblem(credentialless, needed, walt).assert()
            .isEqualTo("the approval does not say what credential its approver approved with")
        val recorded = credentialless.copy(approval = credentialless.approval!!.copy(credentialId = "token-pia"))
        authority.approvals.approverProblem(recorded, needed, walt).assert().isNull()
        // A publisher without the request it came in through gives nothing to check the approver in.
        val detached = me.ahoo.cosec.control.authz.Operator(walt.actor, walt.memberships)
        authority.approvals.approverProblem(recorded, needed, detached).assert()
            .isEqualTo("the approver's right to approve cannot be checked again")
    }

    private fun PolicyDefinition.copyAs(id: String) = PolicyDefinition(PolicyId(id), attachable, condition, statements)
}
