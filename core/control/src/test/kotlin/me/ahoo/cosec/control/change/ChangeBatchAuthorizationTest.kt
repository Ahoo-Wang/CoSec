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
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.DocumentConfirmation
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1218 (`CTL-8`): the decisions of one change rest on one confirmation of the control documents, confirmed again at
 * the end; a change of a thousand bindings no longer confirms them two thousand and one times. A document that moves
 * during the decisions has them taken again on the new confirmation, so a principal revoked meanwhile is denied
 * exactly as it was by a confirmation before every decision. The checker is evaluated on views the test installs at
 * a confirmation and keeps until the next, as a snapshot source does, so a revocation published meanwhile is not seen
 * by a decision until it is confirmed.
 */
class ChangeBatchAuthorizationTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val views = InstalledViews(revisions)
    private val engine = EngineAuthority(revisions, views = views)
    private val confirmations = AtomicInteger()
    private val ids = AtomicInteger()

    /** What runs after the next decision, once: another writer meanwhile. */
    @Volatile
    private var afterDecision: (suspend () -> Unit)? = null

    /** Views kept from one confirmation to the next. */
    private class InstalledViews(revisions: Revisions) : EngineAuthority.HeadViews(revisions) {
        private val installed = ConcurrentHashMap<ViewKey, SnapshotView>()

        override fun peek(key: ViewKey): SnapshotView? = installed[key]

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> =
            super.load(key).thenApply { view -> view.also { installed[key] = it } }

        fun reload() = installed.clear()
    }

    /** Runs [afterDecision] after a decision, in a batch too: a wrapper that passes `batch` on. */
    private inner class Tap(private val inner: ControlAuthorizer) : ControlAuthorizer {
        override suspend fun decide(operator: Operator, action: Action, resource: Resource): ControlDecision {
            val decision = inner.decide(operator, action, resource)
            afterDecision?.let { after ->
                afterDecision = null
                after()
            }
            return decision
        }

        override suspend fun decideAs(
            operator: Operator,
            principal: AuthenticatedPrincipal,
            memberships: Memberships,
            action: Action,
            resource: Resource,
        ): ControlDecision = inner.decideAs(operator, principal, memberships, action, resource)

        override suspend fun <T> batch(block: suspend (ControlAuthorizer) -> T): T =
            inner.batch { via -> block(Tap(via)) }
    }

    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        Tap(engine.authorizer),
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
    )

    private fun policy(id: String, actions: List<String>) = PolicyDefinition(
        PolicyId(id),
        false,
        null,
        listOf(
            StatementSource("", Effect.ALLOW, actions, listOf(ResourceSource("doc/**"), ResourceSource("scope/**")))
        ),
    )

    private fun control(principal: String, policy: PolicyId) = Binding(
        BindingId(principal),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(principal)),
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    private val writer = policy("writer", listOf("cosec:read", "cosec:write", "cosec:grant", "cosec:publish"))

    /** walt writes and grants, and is not the trust root; pia is the platform administrator. */
    private val walt = engine.operator(TenantId.PLATFORM, "walt")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")

    private suspend fun publishPlatform(bindings: List<Binding>, policies: List<PolicyDefinition> = listOf(writer)) {
        revisions.publish(
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform),
            StoreFixtures.candidate(SnapshotKey.Platform, policies, bindings),
            Publication("fixture", ProvenanceKind.PUBLISH, pia.actor),
        )
    }

    private val fixture = listOf(
        control("walt", PolicyId("writer")),
        control("pia", BuiltinPolicies.PLATFORM_ADMIN),
    )

    init {
        runBlocking { publishPlatform(fixture) }
        // A confirmation that reloads the views, and finds the heads' digests: what a snapshot source does.
        engine.confirmation = DocumentConfirmation { keys ->
            confirmations.incrementAndGet()
            views.reload()
            keys.associateWith { key -> revisions.head(key)?.digest ?: "none" }
        }
    }

    private fun entries(count: Int): List<Operation> = (0 until count).flatMap { i ->
        listOf(
            Operation.PutPolicy(allow("p$i")),
            Operation.PutBinding(bind("b$i", "p$i")),
        )
    }

    private suspend fun refused(block: suspend () -> Any): ChangeRejectedException = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e
    }

    /** Before: 2K+1 confirmations for K bindings (3, 21, 201 and 2001 in the report of #1218); now two, whatever K. */
    @Test
    fun `a change confirms the control documents as often for a hundred bindings as for one - #1218`() =
        runBlocking<Unit> {
            val counts = listOf(1, 10, 100).map { bindings ->
                confirmations.set(0)
                changes.propose(pia, ACME, 0, entries(bindings), "$bindings").state.assert()
                    .isEqualTo(ChangeState.CHECKED)
                confirmations.get()
            }
            println("confirmations of a change of 1, 10 and 100 bindings: $counts")
            counts[1].assert().isEqualTo(counts[0])
            counts[2].assert().isEqualTo(counts[0])
            (counts[0] <= 4).assert().isTrue()
        }

    /** The batch ends with a confirmation of its own: the documents are those it began with. */
    @Test
    fun `a change confirms again at the end - #1218`() = runBlocking<Unit> {
        confirmations.set(0)
        changes.propose(walt, ACME, 0, entries(3), "three")
        // Once for the decisions, once for the end: each over the platform, the tenant and the control app.
        confirmations.get().assert().isGreaterThanOrEqualTo(2)
    }

    /** The operator is revoked after the second decision: the change is refused, as a confirmation per decision would. */
    @Test
    fun `an operator revoked during the decisions of a change is refused - #1218`() = runBlocking<Unit> {
        afterDecision = { publishPlatform(listOf(control("pia", BuiltinPolicies.PLATFORM_ADMIN))) }

        refused { changes.propose(walt, ACME, 0, entries(4), "revoked") }.status.assert().isEqualTo(403)

        store.openChanges().assert().isEmpty()
    }

    /** A document that moves without touching the operator's rights has the decisions taken again, and the change goes on. */
    @Test
    fun `a document that moved during the decisions has them taken again - #1218`() = runBlocking<Unit> {
        afterDecision = { publishPlatform(fixture, listOf(writer, policy("unrelated", listOf("order:read")))) }
        confirmations.set(0)

        changes.propose(walt, ACME, 0, entries(4), "moved").state.assert().isEqualTo(ChangeState.CHECKED)

        // Two attempts, each confirmed at its start and its end, at least.
        confirmations.get().assert().isGreaterThanOrEqualTo(4)
    }

    /** The end cannot be confirmed: a permit that rested on documents nobody can vouch for now is a 503. */
    @Test
    fun `documents that cannot be confirmed at the end refuse the change - #1218`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val before = engine.confirmation
        engine.confirmation = DocumentConfirmation { keys ->
            if (calls.incrementAndGet() > 1) error("the snapshot source is down") else before.confirm(keys)
        }

        refused { changes.propose(walt, ACME, 0, entries(2), "end") }.status.assert().isEqualTo(503)

        store.openChanges().assert().isEmpty()
    }

    /** Documents that keep moving are not decided on: after a few attempts, a 503. */
    @Test
    fun `documents that keep moving refuse the change - #1218`() = runBlocking<Unit> {
        val moves = AtomicInteger()
        val before = engine.confirmation
        engine.confirmation = DocumentConfirmation { keys ->
            before.confirm(keys).mapValues { (_, version) -> version to moves.incrementAndGet() }
        }

        refused { changes.propose(walt, ACME, 0, entries(1), "moving") }.status.assert().isEqualTo(503)

        store.openChanges().assert().isEmpty()
    }
}
