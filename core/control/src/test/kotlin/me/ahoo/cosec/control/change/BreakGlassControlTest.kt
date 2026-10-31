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
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
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
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 0007 §8 (BRK-3) through the real engine: break-glass is created only through the control plane, by a platform
 * principal holding `cosec:breakglass`, approved by another one holding `cosec:approve`, within its longest lifetime,
 * and recorded by a REQUIRED change listener before every step that creates it.
 */
class BreakGlassControlTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()
    private val opened = ArrayList<ChangeListeners>()

    /** A REQUIRED listener that records, or fails on the event kinds in [failing]. */
    private class Audit(val failing: MutableSet<ChangeEvent.Kind> = HashSet()) : ChangeListener {
        override val name: String = "audit"
        override val delivery: Delivery = Delivery.REQUIRED
        val events = CopyOnWriteArrayList<ChangeEvent>()

        override suspend fun onChange(event: ChangeEvent) {
            check(event.kind !in failing) { "the audit store is down" }
            events += event
        }
    }

    private val audit = Audit()

    private fun changes(
        listeners: List<ChangeListener> = listOf(
            audit
        ),
        options: ChangeOptions = ChangeOptions(shadow = IMMEDIATE_WINDOW)
    ): Changes {
        val registered = ChangeListeners(listeners).also { opened += it }
        return Changes(
            store,
            revisions,
            { ChangeId("c${ids.incrementAndGet()}") },
            engine.authorizer,
            clock,
            options,
            listeners = registered
        )
    }

    private val changes = changes()
    private val breakGlass = BreakGlass(changes, revisions)

    private val plato = engine.operator(TenantId.PLATFORM, "plato")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")
    private val rita = engine.operator(TenantId.PLATFORM, "rita")
    private val alice = engine.operator(TenantId("acme"), "alice")

    init {
        runBlocking {
            // rita may request and revoke break-glass, and nothing else.
            val requester = PolicyDefinition(
                PolicyId("break-glass-requester"),
                false,
                null,
                listOf(
                    StatementSource(
                        "",
                        Effect.ALLOW,
                        listOf("cosec:breakglass", "cosec:publish"),
                        listOf(ResourceSource("doc/platform"))
                    )
                ),
            )
            revisions.publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(
                    SnapshotKey.Platform,
                    listOf(requester),
                    listOf(
                        control("plato", "plato", BuiltinPolicies.PLATFORM_ADMIN),
                        control("pia", "pia", BuiltinPolicies.PLATFORM_ADMIN),
                        control("rita", "rita", PolicyId("break-glass-requester")),
                        Binding(
                            BindingId("alice"),
                            SubjectRef.Principal(TenantId("acme"), PrincipalId("alice")),
                            BindingScope(Match.Any, Match.Any),
                            BuiltinPolicies.PLATFORM_ADMIN
                        ),
                    ),
                ),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor),
            )
        }
    }

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    private fun control(id: String, principal: String, policy: PolicyId) = Binding(
        BindingId(id),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(principal)),
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    private fun inHours(hours: Long) = clock.now.plus(Duration.ofHours(hours))

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    private suspend fun glass(): Binding? =
        revisions.head(SnapshotKey.Platform)!!.document.bindings.singleOrNull { it.breakGlass }

    @Test
    fun `two people create it, each step recorded first`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(2), "incident 42")
        requested.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        // Neither the requester nor someone without cosec:approve approves it.
        refused { changes.approve(rita, requested.id, requested.candidate!!) }.assert().isEqualTo(403)
        changes.approve(pia, requested.id, requested.candidate!!).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(rita, requested.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        glass()!!.subject.assert().isEqualTo(SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("rita")))
        revisions.head(SnapshotKey.Platform)!!.document.provenance!!.approvedBy.assert().containsExactly(pia.actor)
        // The gated steps reached it, in order, before each took effect; the others reach it as they can.
        audit.events.map { it.kind }.filter { it.gated }.assert().containsExactly(
            ChangeEvent.Kind.BREAK_GLASS_REQUESTED,
            ChangeEvent.Kind.BREAK_GLASS_APPROVED,
            ChangeEvent.Kind.PUBLISHING,
        )
    }

    /** RC1-29: the binding's id is the control plane's, so a request never replaces another binding. */
    @Test
    fun `a request never takes the id of a binding there is`() = runBlocking<Unit> {
        val expiresAt = inHours(2)
        val taken = BindingId("break-glass-rita-${expiresAt.toEpochMilli()}")
        val head = revisions.head(SnapshotKey.Platform)!!
        val deny = Binding(taken, SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), BuiltinPolicies.BLACKLIST)
        revisions.publish(
            SnapshotKey.Platform,
            head,
            StoreFixtures.candidate(SnapshotKey.Platform, head.document.policies, head.document.bindings + deny),
            Publication("deny", ProvenanceKind.PUBLISH, plato.actor),
        )
        refused { breakGlass.request(rita, PrincipalId("rita"), expiresAt, "incident") }.assert().isEqualTo(409)
        revisions.head(SnapshotKey.Platform)!!.document.bindings.single { it.id == taken }.policy
            .assert().isEqualTo(BuiltinPolicies.BLACKLIST)
    }

    /**
     * RC1-29 (review item 16): however a change is made, a break-glass binding never replaces an ordinary one: the
     * checks say so, so a rebase or an internal proposal is held to it as a request is.
     */
    @Test
    fun `a break-glass binding never replaces another binding`() = runBlocking<Unit> {
        val head = revisions.head(SnapshotKey.Platform)!!
        val alice = head.document.bindings.single { it.id == BindingId("alice") }
        val turned = Binding(
            alice.id,
            SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("rita")),
            BindingScope(Match.Any, Match.Any),
            BuiltinPolicies.ROOT,
            inHours(1).toEpochMilli(),
            breakGlass = true,
        )
        val change = changes.propose(
            rita,
            SnapshotKey.Platform,
            head.revision,
            listOf(Operation.PutBinding(turned)),
            "incident",
            kind = ProvenanceKind.BREAK_GLASS,
        )
        change.state.assert().isEqualTo(ChangeState.DRAFT)
        change.findings.map { it.message }.assert().contains("a break-glass binding never replaces another binding")
    }

    /** Review item 20: an expired break-glass binding grants nothing, so revoking it takes no busy slot. */
    @Test
    fun `revoking an expired binding waits for the slot`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "incident")
        changes.approve(pia, requested.id, requested.candidate!!)
        changes.publish(rita, requested.id)
        val binding = glass()!!.id
        clock.now = inHours(2)
        val head = revisions.head(SnapshotKey.Platform)!!.revision
        val holding = changes.propose(
            plato,
            SnapshotKey.Platform,
            head,
            listOf(Operation.PutPolicy(allowAll("widen"))),
            "slow"
        )
        changes.stage(plato, holding.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        refused { breakGlass.revoke(rita, binding, "tidy") }.assert().isEqualTo(409)
        changes.get(holding.id).state.assert().isEqualTo(ChangeState.SHADOWING)
    }

    @Test
    fun `only the platform's principals with cosec breakglass request it`() = runBlocking<Unit> {
        // alice holds platform-admin through a wrongly written binding; the hard rule still refuses her.
        refused { breakGlass.request(alice, PrincipalId("alice"), inHours(1), "mine") }.assert().isEqualTo(403)
        val nobody = engine.operator(TenantId.PLATFORM, "nobody")
        refused { breakGlass.request(nobody, PrincipalId("nobody"), inHours(1), "mine") }.assert().isEqualTo(403)
        // An ordinary change cannot carry a break-glass binding.
        val binding = Binding(
            BindingId("sneaky"),
            SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
            BindingScope(Match.Any, Match.Any),
            BuiltinPolicies.ROOT,
            inHours(1).toEpochMilli(),
            true
        )
        val change = changes.propose(plato, SnapshotKey.Platform, 1, listOf(Operation.PutBinding(binding)), "sneaky")
        change.state.assert().isEqualTo(ChangeState.DRAFT)
        change.errors.map { it.check }.assert().contains("V5")
        // A break-glass change carries nothing else.
        val mixed = changes.propose(
            plato,
            SnapshotKey.Platform,
            1,
            listOf(Operation.PutBinding(binding), Operation.RemoveBinding(BindingId("rita"))),
            "mixed",
            ProvenanceKind.BREAK_GLASS
        )
        mixed.errors.map { it.path }.assert().contains("operations[1]")
    }

    @Test
    fun `its lifetime is bounded, when requested and when published`() = runBlocking<Unit> {
        breakGlass.request(rita, PrincipalId("rita"), inHours(5), "too long").errors.map { it.path }
            .assert().contains("$.bindings[4].expiresAt")
        val longer = changes(
            options = ChangeOptions(shadow = IMMEDIATE_WINDOW, breakGlassMaxDuration = Duration.ofHours(24))
        )
        BreakGlass(
            longer,
            revisions
        ).request(
            rita,
            PrincipalId("rita"),
            inHours(20),
            "long"
        ).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        assertThrownBy<IllegalArgumentException> { ChangeOptions(breakGlassMaxDuration = Duration.ofHours(25)) }
    }

    @Test
    fun `an expired request does not publish`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "short")
        changes.approve(pia, requested.id, requested.candidate!!)
        clock.now = clock.now.plus(Duration.ofHours(2))
        val outcome = changes.publish(rita, requested.id)
        outcome.state.assert().isEqualTo(ChangeState.DRAFT)
        glass().assert().isNull()
    }

    @Test
    fun `without a REQUIRED listener, or when it fails, nothing is created`() = runBlocking<Unit> {
        val unaudited = BreakGlass(changes(emptyList()), revisions)
        refused { unaudited.request(rita, PrincipalId("rita"), inHours(1), "x") }.assert().isEqualTo(503)
        audit.failing += ChangeEvent.Kind.BREAK_GLASS_REQUESTED
        refused { breakGlass.request(rita, PrincipalId("rita"), inHours(1), "x") }.assert().isEqualTo(503)
        store.change(ChangeId("c${ids.get()}")).assert().isNull()
        audit.failing.clear()
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "x")
        audit.failing += ChangeEvent.Kind.BREAK_GLASS_APPROVED
        refused { changes.approve(pia, requested.id, requested.candidate!!) }.assert().isEqualTo(503)
        changes.get(requested.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        audit.failing.clear()
        changes.approve(pia, requested.id, requested.candidate!!)
        audit.failing += ChangeEvent.Kind.PUBLISHING
        refused { changes.publish(rita, requested.id) }.assert().isEqualTo(503)
        changes.get(requested.id).state.assert().isEqualTo(ChangeState.READY)
        glass().assert().isNull()
    }

    /**
     * RC1-30 (`BRK-3`): a break-glass approval is recorded by a REQUIRED listener once its digest is checked, before it is
     * stored: an instance without one refuses it, and an approval of another candidate records nothing.
     */
    @Test
    fun `an approval of break-glass is recorded first, and only for the candidate it approves`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "incident")
        val drifted = changes(listeners = emptyList())
        refused { drifted.approve(pia, requested.id, requested.candidate!!) }.assert().isEqualTo(503)
        refused { changes.approve(pia, requested.id, "0".repeat(64)) }.assert().isEqualTo(409)
        audit.events.map { it.kind }.assert().doesNotContain(ChangeEvent.Kind.BREAK_GLASS_APPROVED)
        changes.get(requested.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        changes.approve(pia, requested.id, requested.candidate!!).state.assert().isEqualTo(ChangeState.READY)
        audit.events.map { it.kind }.assert().contains(ChangeEvent.Kind.BREAK_GLASS_APPROVED)
    }

    /**
     * RC1-12: a platform change in its shadow window holds the platform document's slot; revoking break-glass does not
     * wait for it. The holder becomes CONFLICTED, its candidate withdrawn, recorded as a failure that names the revoke.
     */
    @Test
    fun `a revoke takes the slot another change holds`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "incident")
        changes.approve(pia, requested.id, requested.candidate!!)
        changes.publish(rita, requested.id)
        val binding = glass()!!.id
        val head = revisions.head(SnapshotKey.Platform)!!.revision
        val holding = changes.propose(
            plato,
            SnapshotKey.Platform,
            head,
            listOf(Operation.PutPolicy(allowAll("widen"))),
            "slow"
        )
        changes.stage(plato, holding.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        store.candidate(SnapshotKey.Platform).assert().isNotNull()

        breakGlass.revoke(rita, binding, "compromised").state.assert().isEqualTo(ChangeState.PUBLISHED)
        glass().assert().isNull()
        changes.get(holding.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        store.candidate(SnapshotKey.Platform).assert().isNull()
        // Not gated: it reaches the listener as it can.
        kotlinx.coroutines.withTimeout(5_000) {
            while (audit.events.none { it.change == holding.id && it.kind == ChangeEvent.Kind.FAILED }) {
                kotlinx.coroutines.delay(10)
            }
        }
        audit.events.single { it.change == holding.id && it.kind == ChangeEvent.Kind.FAILED }.note
            .assert().startsWith("preempted by ")
    }

    private fun allowAll(id: String) = PolicyDefinition(
        PolicyId(id),
        false,
        null,
        listOf(StatementSource("", Effect.ALLOW, listOf("*")))
    )

    @Test
    fun `one person revokes it at once`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "incident")
        changes.approve(pia, requested.id, requested.candidate!!)
        changes.publish(rita, requested.id)
        val binding = glass()!!.id
        val revoked = breakGlass.revoke(rita, binding, "resolved")
        revoked.state.assert().isEqualTo(ChangeState.PUBLISHED)
        glass().assert().isNull()
        // Revoking is audited too, but nothing waits for it: it only narrows.
        audit.events.map { it.kind }.filter { it.gated }.last().assert().isEqualTo(ChangeEvent.Kind.PUBLISHING)
        revisions.head(SnapshotKey.Platform)!!.document.provenance!!.kind.assert().isEqualTo(ProvenanceKind.BREAK_GLASS)
    }
}
