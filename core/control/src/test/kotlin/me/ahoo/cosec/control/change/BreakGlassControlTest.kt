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
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.Versioned
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
    private val memory = InMemoryControlStore(clock)

    /** How many times a staging slot was read. */
    private val slotReads = AtomicInteger()

    /** What runs once right after the next read of a change, before its answer: another call meanwhile. */
    @Volatile
    private var afterChange: (suspend () -> Unit)? = null
    private val store: ControlStore = object : ControlStore by memory {
        override suspend fun stage(key: SnapshotKey): Versioned<StageSlot>? =
            memory.stage(key).also { slotReads.incrementAndGet() }

        override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? {
            val read = memory.change(id)
            afterChange?.let { race ->
                afterChange = null
                race()
            }
            return read
        }
    }
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()
    private val opened = ArrayList<ChangeListeners>()

    /** A REQUIRED listener that records, or fails on the event kinds in [failing]. */
    private class Audit(
        val failing: MutableSet<ChangeEvent.Kind> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    ) : ChangeListener {
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

    /** Another requester: may request and revoke break-glass, and read none of rita's changes. */
    private val rhea = engine.operator(TenantId.PLATFORM, "rhea")
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
                        control("rhea", "rhea", PolicyId("break-glass-requester")),
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
            .assert().contains("$.bindings[5].expiresAt")
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

    /**
     * Review L3: a revoke of a live binding holding the slot is not taken by a narrowing change, by a holder of
     * `cosec:skip-shadow` included: the principal it revokes could otherwise hold its own revocation off.
     */
    @Test
    fun `a narrowing change does not take the slot of a live revoke`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "incident")
        changes.approve(pia, requested.id, requested.candidate!!)
        changes.publish(rita, requested.id)
        val binding = glass()!!.id
        // The revoke is staged, and its publish waits for the audit store.
        audit.failing += ChangeEvent.Kind.PUBLISHING
        refused { breakGlass.revoke(rita, binding, "compromised") }.assert().isEqualTo(503)
        audit.failing.clear()
        val revoke = store.stage(SnapshotKey.Platform)!!.value.holder!!
        changes.get(revoke).state.assert().isEqualTo(ChangeState.READY)
        val deny = PolicyDefinition(
            PolicyId("ban"),
            false,
            null,
            listOf(StatementSource("", Effect.DENY, listOf("order:delete")))
        )
        val ban = changes.propose(
            plato,
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform)!!.revision,
            listOf(
                Operation.PutPolicy(deny),
                Operation.PutBinding(
                    Binding(BindingId("ban"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId("ban"))
                ),
            ),
            "ban"
        )
        refused { changes.skipShadow(plato, ban.id, "incident") }.assert().isEqualTo(409)
        changes.get(revoke).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(rita, revoke).state.assert().isEqualTo(ChangeState.PUBLISHED)
        glass().assert().isNull()
    }

    /** A break-glass binding of [principal], published, expiring in [hours]. */
    private suspend fun granted(hours: Long, principal: PrincipalId = PrincipalId("rita")): BindingId {
        val requested = breakGlass.request(rita, principal, inHours(hours), "incident $hours")
        changes.approve(pia, requested.id, requested.candidate!!)
        changes.publish(rita, requested.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        return revisions.head(SnapshotKey.Platform)!!.document.bindings
            .single { it.breakGlass && it.expiresAt == inHours(hours).toEpochMilli() }.id
    }

    /** A revoke of [binding] by [revoker] whose publish stopped at the audit store: READY, holding the slot. */
    private suspend fun stalled(binding: BindingId, revoker: Operator = rita): ChangeId {
        audit.failing += ChangeEvent.Kind.PUBLISHING
        refused { breakGlass.revoke(revoker, binding, "compromised") }.assert().isEqualTo(503)
        audit.failing.clear()
        val revoke = store.stage(SnapshotKey.Platform)!!.value.holder!!
        changes.get(revoke).state.assert().isEqualTo(ChangeState.READY)
        return revoke
    }

    /** Review N1, scenario A: revoking the binding again publishes the revoke that stalled, rather than a 409. */
    @Test
    fun `revoking again publishes a stalled revoke of the same binding`() = runBlocking<Unit> {
        val binding = granted(1)
        val revoke = stalled(binding)
        val again = breakGlass.revoke(pia, binding, "compromised, again")
        again.id.assert().isEqualTo(revoke)
        again.state.assert().isEqualTo(ChangeState.PUBLISHED)
        glass().assert().isNull()
        // R4-5: its records name the second revoker and their reason, not only the first's.
        val note = "compromised; published again by (platform)/pia: compromised, again"
        audit.events.last { it.kind == ChangeEvent.Kind.PUBLISHING }.note.assert().isEqualTo(note)
        delivered(revoke, ChangeEvent.Kind.REVOKED).let {
            it.note.assert().isEqualTo(note)
            it.operator.assert().isEqualTo(pia.actor)
        }
    }

    /** The event of [kind] for [change], once it reached the listener: one that does not gate its step comes later. */
    private suspend fun delivered(change: ChangeId, kind: ChangeEvent.Kind): ChangeEvent {
        kotlinx.coroutines.withTimeout(5_000) {
            while (audit.events.none { it.change == change && it.kind == kind }) {
                kotlinx.coroutines.delay(10)
            }
        }
        return audit.events.single { it.change == change && it.kind == kind }
    }

    /**
     * Review N1, scenario A: a revoker who may not see the stalled revoke revokes with a change of its own, which takes
     * the slot from it: a revoke of a binding that still grants is taken by another such revoke, and by nothing else.
     */
    @Test
    fun `a revoke takes the slot of a stalled revoke`() = runBlocking<Unit> {
        val binding = granted(1)
        val revoke = stalled(binding)
        val own = breakGlass.revoke(rhea, binding, "compromised, again")
        own.id.assert().isNotEqualTo(revoke)
        own.state.assert().isEqualTo(ChangeState.PUBLISHED)
        changes.get(revoke).state.assert().isEqualTo(ChangeState.CONFLICTED)
        glass().assert().isNull()
    }

    /**
     * Review N1, scenario B: a revoke of a binding that has expired grants no one anything back, so it holds the slot
     * against nothing: a revoke of a live binding takes it.
     */
    @Test
    fun `a revoke of an expired binding does not hold off a revoke`() = runBlocking<Unit> {
        val expiring = granted(1)
        val live = granted(3)
        clock.now = inHours(2)
        // rita stages a revoke of the binding that has expired, and does not publish it.
        val head = revisions.head(SnapshotKey.Platform)!!.revision
        val stale = changes.propose(
            rita,
            SnapshotKey.Platform,
            head,
            listOf(Operation.RemoveBinding(expiring)),
            "tidy",
            ProvenanceKind.BREAK_GLASS
        )
        changes.stage(rita, stale.id).state.assert().isEqualTo(ChangeState.READY)
        breakGlass.revoke(rhea, live, "compromised").state.assert().isEqualTo(ChangeState.PUBLISHED)
        changes.get(stale.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        revisions.head(SnapshotKey.Platform)!!.document.bindings.none { it.id == live }.assert().isTrue()
    }

    /**
     * R4-3: a revoke of a binding that has expired grants no one anything back, so it does not take the slot of a
     * revoke of a live binding, even staged by a holder of `cosec:skip-shadow`, whose narrowing changes may preempt.
     */
    @Test
    fun `a revoke of an expired binding does not take the slot of a live revoke`() = runBlocking<Unit> {
        val expiring = granted(1)
        val live = granted(3)
        val revoke = stalled(live)
        clock.now = inHours(2)
        refused { breakGlass.revoke(plato, expiring, "tidy") }.assert().isEqualTo(409)
        changes.get(revoke).state.assert().isEqualTo(ChangeState.READY)
        store.stage(SnapshotKey.Platform)!!.value.holder.assert().isEqualTo(revoke)
    }

    /** R4-3: a revoke of an expired binding holds the slot like any change, so a narrowing change takes it. */
    @Test
    fun `a narrowing change takes the slot of a revoke of an expired binding`() = runBlocking<Unit> {
        val expiring = granted(1)
        clock.now = inHours(2)
        val stale = changes.propose(
            rita,
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform)!!.revision,
            listOf(Operation.RemoveBinding(expiring)),
            "tidy",
            ProvenanceKind.BREAK_GLASS
        )
        changes.stage(rita, stale.id).state.assert().isEqualTo(ChangeState.READY)
        val ban = changes.propose(
            plato,
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform)!!.revision,
            ban(),
            "ban"
        )
        changes.skipShadow(plato, ban.id, "incident").state.assert().isEqualTo(ChangeState.READY)
        changes.get(stale.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        changes.publish(plato, ban.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    /** A new DENY and its binding: a change that only narrows. */
    private fun ban() = listOf(
        Operation.PutPolicy(
            PolicyDefinition(
                PolicyId("ban"),
                false,
                null,
                listOf(StatementSource("", Effect.DENY, listOf("order:delete")))
            )
        ),
        Operation.PutBinding(
            Binding(BindingId("ban"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId("ban"))
        ),
    )

    /**
     * R4-6: a caller who may not revoke is refused before the slot is read, so whether a stalled revoke exists makes no
     * difference to what it sees, its timing included.
     */
    @Test
    fun `a revoker is authorized before the slot is read`() = runBlocking<Unit> {
        val binding = granted(1)
        val revoke = stalled(binding)
        val nobody = engine.operator(TenantId.PLATFORM, "nobody")
        val reads = slotReads.get()
        refused { breakGlass.revoke(nobody, binding, "mine") }.assert().isEqualTo(403)
        refused { breakGlass.revoke(alice, binding, "mine") }.assert().isEqualTo(403)
        slotReads.get().assert().isEqualTo(reads)
        changes.get(revoke).state.assert().isEqualTo(ChangeState.READY)
    }

    /**
     * R4-6: a stalled revoke that moves while it is read past its expiry is no stalled revoke: revoking makes a new one
     * rather than answering the 409 of that expiry.
     */
    @Test
    fun `a holder that moves while it expires is no stalled revoke`() = runBlocking<Unit> {
        val expiring = changes(options = ChangeOptions(shadow = IMMEDIATE_WINDOW, expireAfter = Duration.ofMinutes(30)))
        val binding = granted(2)
        val revoke = stalled(binding)
        clock.now = clock.now.plus(Duration.ofHours(1))
        // Another instance abandons the stalled revoke right after this one read it, before it is marked expired.
        afterChange = {
            val current = memory.change(revoke)!!
            memory.putChange(current.digest, current.value.copy(state = ChangeState.ABANDONED)).assert().isTrue()
        }
        val own = BreakGlass(expiring, revisions).revoke(rita, binding, "compromised, again")
        own.id.assert().isNotEqualTo(revoke)
        own.state.assert().isEqualTo(ChangeState.PUBLISHED)
        glass().assert().isNull()
    }

    /**
     * RC1-51: a revoke of a live binding is abandoned by its author alone. The principal it revokes holds
     * `cosec:publish` (here through `cosec:platform-admin`, as through the binding's `cosec:root`) and is refused, as is
     * every other publisher: nobody else needs to, since a new revoke takes its slot.
     */
    @Test
    fun `only its author abandons a revoke of a live binding`() = runBlocking<Unit> {
        val binding = granted(1, PrincipalId("plato"))
        val revoke = stalled(binding)
        refused { changes.abandon(plato, revoke) }.assert().isEqualTo(403)
        refused { changes.abandon(pia, revoke) }.assert().isEqualTo(403)
        changes.get(revoke).state.assert().isEqualTo(ChangeState.READY)
        audit.events.none { it.kind == ChangeEvent.Kind.ABANDONED }.assert().isTrue()
        changes.abandon(rita, revoke).state.assert().isEqualTo(ChangeState.ABANDONED)
        store.stage(SnapshotKey.Platform)?.value?.holder.assert().isNull()
        audit.events.single { it.kind == ChangeEvent.Kind.ABANDONED }.let {
            it.operator.assert().isEqualTo(rita.actor)
            it.note.assert().isEqualTo("author (platform)/rita: compromised")
        }
    }

    /**
     * RC1-51: abandoning a break-glass change is recorded by a REQUIRED listener before it is stored: without one, or
     * when it fails, a 503 and the change stays as it was. A revoke of an expired binding is anyone's with
     * `cosec:publish` to abandon, recorded the same way.
     */
    @Test
    fun `abandoning break-glass is recorded first`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "incident")
        refused { changes(emptyList()).abandon(rita, requested.id) }.assert().isEqualTo(503)
        audit.failing += ChangeEvent.Kind.ABANDONED
        refused { changes.abandon(rita, requested.id) }.assert().isEqualTo(503)
        changes.get(requested.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        audit.failing.clear()
        changes.abandon(rita, requested.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        audit.events.single { it.kind == ChangeEvent.Kind.ABANDONED }.note.assert().isEqualTo(
            "author (platform)/rita: incident"
        )

        val expiring = granted(1)
        val revoke = stalled(expiring)
        clock.now = inHours(2)
        changes.abandon(plato, revoke).state.assert().isEqualTo(ChangeState.ABANDONED)
        audit.events.single { it.kind == ChangeEvent.Kind.ABANDONED && it.change == revoke }.operator
            .assert().isEqualTo(plato.actor)
    }

    /** RC1-51: abandoning any other change is recorded too, after it is stored and best effort. */
    @Test
    fun `abandoning any other change is recorded after, best effort`() = runBlocking<Unit> {
        val head = revisions.head(SnapshotKey.Platform)!!.revision
        val failing = changes.propose(
            plato,
            SnapshotKey.Platform,
            head,
            listOf(Operation.PutPolicy(allowAll("a"))),
            "a"
        )
        audit.failing += ChangeEvent.Kind.ABANDONED
        changes.abandon(pia, failing.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        audit.failing.clear()
        val recorded = changes.propose(
            plato,
            SnapshotKey.Platform,
            head,
            listOf(Operation.PutPolicy(allowAll("b"))),
            "b"
        )
        changes.stage(plato, recorded.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        changes.abandon(pia, recorded.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        store.stage(SnapshotKey.Platform)?.value?.holder.assert().isNull()
        delivered(recorded.id, ChangeEvent.Kind.ABANDONED).let {
            it.operator.assert().isEqualTo(pia.actor)
            it.note.assert().isEqualTo("author (platform)/plato: b")
        }
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
