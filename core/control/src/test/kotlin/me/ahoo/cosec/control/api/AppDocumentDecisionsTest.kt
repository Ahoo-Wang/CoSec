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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger

/**
 * CTL-3, R1-1 of #1194's review: `simulate` and `explain` read the documents enforcement reads, by its one rule
 * (`ViewDocuments`). A tenant that binds the order app's permission policy to `Anyone`, or to bob himself, makes bob's
 * request in `order` read the app's document although he has no role there (RC1-17): enforcement permits it, and so
 * must the control plane, not `Indeterminate(NotLoaded(APP))`.
 */
class AppDocumentDecisionsTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()
    private val options = ChangeOptions(shadow = IMMEDIATE_WINDOW)
    private val changes =
        Changes(store, revisions, { ChangeId("c${ids.incrementAndGet()}") }, engine.authorizer, clock, options)
    private val plane = DefaultControlPlane(changes, revisions, ChangeAuthority(engine.authorizer, options))
    private val plato = engine.operator(TenantId.PLATFORM, "plato")

    private val read = PolicyDefinition(
        PolicyId("perm:order/read"),
        false,
        null,
        listOf(StatementSource("", Effect.ALLOW, listOf("order:read"), listOf(ResourceSource("order/**")))),
    )

    /** The tenant's binding of the order app's permission policy, to Anyone or to bob. */
    private fun grant(subject: String) = Binding(
        BindingId("reads"),
        if (subject == "anyone") SubjectRef.Anyone else SubjectRef.Principal(ACME, PrincipalId("bob")),
        BindingScope(Match.Exactly(ORDER), Match.Any),
        PolicyId("perm:order/read"),
    )

    private suspend fun publish(key: SnapshotKey, policies: List<PolicyDefinition>, bindings: List<Binding>) {
        revisions.publish(
            key,
            revisions.head(key),
            StoreFixtures.candidate(key, policies, bindings),
            Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor),
        )
    }

    private suspend fun bootstrap(tenantBindings: List<Binding>) {
        val admin = Binding(
            BindingId("plato"),
            SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
            BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
            BuiltinPolicies.PLATFORM_ADMIN,
        )
        publish(SnapshotKey.Platform, emptyList(), listOf(admin))
        publish(SnapshotKey.App("order"), listOf(read), emptyList())
        publish(TENANT, emptyList(), tenantBindings)
    }

    /** bob, with no role anywhere, reads an order in the order app's default space. */
    private fun bob() = SimulatedRequest(
        tenant = ACME,
        principal = "bob",
        app = "order",
        action = Action("order", "read"),
        resource = Resource("order", "1", emptyMap()),
        time = clock.now.toEpochMilli(),
    )

    @ParameterizedTest
    @ValueSource(strings = ["anyone", "bob"])
    fun `simulate reads the app document the tenant's binding needs, as enforcement does`(
        subject: String
    ) = runBlocking<Unit> {
        bootstrap(listOf(grant(subject)))
        val simulated = plane.simulate(plato, bob())
        simulated.enforced.decision.assert().isEqualTo("Permit")
        simulated.enforced.by!!.policy.assert().isEqualTo("perm:order/read")
        simulated.enforced.by!!.via.assert().isEqualTo("reads")
        // A tenant without the binding reads no app document for bob: the implicit deny, never NotLoaded.
        val without = changes.propose(plato, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("reads"))), "revoke")
        val revoked = plane.simulate(plato, bob(), without.id)
        revoked.candidate!!.decision.assert().isEqualTo("Deny")
        revoked.candidate!!.cause.assert().isNull()
        revoked.differs.assert().isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["anyone", "bob"])
    fun `a simulated tenant change that adds such a binding reads the app document too`(
        subject: String
    ) = runBlocking<Unit> {
        bootstrap(emptyList())
        val before = plane.simulate(plato, bob())
        before.enforced.decision.assert().isEqualTo("Deny")
        before.enforced.cause.assert().isNull()
        val change = changes.propose(plato, TENANT, 1, listOf(Operation.PutBinding(grant(subject))), "grant")
        val simulated = plane.simulate(plato, bob(), change.id)
        simulated.candidate!!.decision.assert().isEqualTo("Permit")
        simulated.candidate!!.by!!.policy.assert().isEqualTo("perm:order/read")
        simulated.differs.assert().isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["anyone", "bob"])
    fun `explain decides at the app revision an audit event recorded`(subject: String) = runBlocking<Unit> {
        bootstrap(listOf(grant(subject)))
        // The app's next revision turns the permission into a DENY: the audited revisions still explain the permit.
        val denying = PolicyDefinition(
            read.id,
            false,
            null,
            listOf(StatementSource("", Effect.DENY, listOf("order:read"), listOf(ResourceSource("order/**")))),
        )
        publish(SnapshotKey.App("order"), listOf(denying), emptyList())
        val audited = plane.explain(plato, bob(), SnapshotVersion(1, 1, 1))
        audited.decision.decision.assert().isEqualTo("Permit")
        audited.decision.by!!.policy.assert().isEqualTo("perm:order/read")
        audited.version.assert().isEqualTo(SnapshotVersion(1, 1, 1))
        val now = plane.explain(plato, bob())
        now.version.assert().isEqualTo(SnapshotVersion(1, 2, 1))
        now.decision.decision.assert().isEqualTo("Deny")
    }

    private companion object {
        val ACME = TenantId("acme")
        val TENANT: SnapshotKey = SnapshotKey.Tenant("acme")
        val ORDER = AppId("order")
    }
}
