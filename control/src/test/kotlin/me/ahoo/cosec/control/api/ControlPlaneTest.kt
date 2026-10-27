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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
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
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 0007 §10.1, §10.3 through the real engine: the control plane's reads are authorized like its writes, and
 * `simulate`, `explain` and `analyze` decide with the enforcing flow while showing only what the caller may read.
 */
class ControlPlaneTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()
    private val options = ChangeOptions(shadow = IMMEDIATE_WINDOW)
    val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        engine.authorizer,
        clock,
        options
    )
    val plane = DefaultControlPlane(changes, revisions, ChangeAuthority(engine.authorizer, options))

    val plato = engine.operator(TenantId.PLATFORM, "plato")
    val alice = engine.operator(ACME, "alice", roles = listOf("admin", "ops@team-a"))
    val mallory = engine.operator(GLOBEX, "mallory", roles = listOf("admin"))

    init {
        runBlocking {
            val noDelete = PolicyDefinition(
                PolicyId("no-delete"),
                false,
                null,
                listOf(StatementSource("", Effect.DENY, listOf("order:delete"), listOf(ResourceSource("order/**")))),
            )
            bootstrap(
                SnapshotKey.Platform,
                listOf(noDelete),
                listOf(
                    control(
                        "plato",
                        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
                        BuiltinPolicies.PLATFORM_ADMIN
                    ),
                    Binding(
                        BindingId("no-delete"),
                        SubjectRef.Anyone,
                        BindingScope(Match.Any, Match.Any),
                        PolicyId("no-delete")
                    ),
                ),
            )
            for (tenant in listOf(ACME, GLOBEX)) {
                bootstrap(
                    SnapshotKey.Tenant(tenant.value),
                    listOf(allow("orders", action = "order:*", resource = "order/**")),
                    listOf(
                        control("admins", SubjectRef.Role(tenant, RoleId("admin")), BuiltinPolicies.TENANT_ADMIN),
                        Binding(
                            BindingId("ops"),
                            SubjectRef.Role(tenant, RoleId("ops")),
                            BindingScope(Match.Exactly(ORDER), Match.Any),
                            PolicyId("orders")
                        ),
                    ),
                )
            }
        }
    }

    suspend fun bootstrap(key: SnapshotKey, policies: List<PolicyDefinition>, bindings: List<Binding>) {
        revisions.publish(
            key,
            revisions.head(key),
            StoreFixtures.candidate(key, policies, bindings),
            Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor)
        )
    }

    private fun control(id: String, subject: SubjectRef, policy: PolicyId) =
        Binding(
            BindingId(id),
            subject,
            BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
            policy
        )

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    /** bob of [tenant] in team-a of the order app, as `ops` there. */
    private fun bob(action: String, tenant: TenantId = ACME) = SimulatedRequest(
        tenant = tenant,
        principal = "bob",
        roles = listOf("ops@team-a"),
        app = "order",
        space = "team-a",
        action = Action("order", action),
        resource = Resource("order", "1", emptyMap()),
        time = clock.now.toEpochMilli(),
    )

    @Test
    fun `reads are authorized, and an unreadable change is not there`() = runBlocking<Unit> {
        plane.read(alice, TENANT).bindings.map { it.id.value }.assert().contains("ops")
        refused { plane.read(alice, SnapshotKey.Platform) }.assert().isEqualTo(403)
        refused { plane.read(mallory, TENANT) }.assert().isEqualTo(403)
        plane.history(alice, TENANT).single().revision.assert().isEqualTo(1)
        refused { plane.read(alice, TENANT, 9) }.assert().isEqualTo(410)
        refused { plane.read(alice, SnapshotKey.Tenant("nobody")) }.assert().isEqualTo(403)

        val change = plane.propose(alice, TENANT, 1, listOf(Operation.PutPolicy(allow("more"))), "more")
        plane.get(alice, change.id).id.assert().isEqualTo(change.id)
        refused { plane.get(mallory, change.id) }.assert().isEqualTo(404)
        refused { plane.get(mallory, ChangeId("missing")) }.assert().isEqualTo(404)
        plane.startShadow(alice, change.id)
        changes.shadowed(change.id)
        plane.publish(alice, change.id)
        plane.history(alice, TENANT).map { it.revision }.assert().containsExactly(2L, 1L)
        plane.history(alice, TENANT, before = 2, limit = 1).map { it.revision }.assert().containsExactly(1L)
        plane.read(alice, TENANT, 1).policies.map { it.id.value }.assert().containsExactly("orders")
        val diff = plane.diff(alice, TENANT, 1, 2)
        diff.policies.added.assert().containsExactly("more")
        diff.bindings.assert().isEqualTo(EntryChanges(emptyList(), emptyList(), emptyList()))
        diff.sections.assert().isFalse()
        plane.propagation(alice, TENANT, 2).recent.assert().isEqualTo(0)
    }

    @Test
    fun `simulate decides with the enforcing flow and shows only what the caller may read`() = runBlocking<Unit> {
        val read = plane.simulate(alice, bob("read"))
        read.enforced.decision.assert().isEqualTo("Permit")
        read.enforced.by!!.policy.assert().isEqualTo("orders")
        read.enforced.by!!.via.assert().isEqualTo("ops")
        // The platform's DENY decides; alice may not read the platform, so the statement is not named.
        val delete = plane.simulate(alice, bob("delete"))
        delete.enforced.decision.assert().isEqualTo("Deny")
        delete.enforced.by.assert().isNull()
        delete.enforced.redacted.assert().isTrue()
        delete.enforcedTrace!!.redacted.assert().isGreaterThan(0)
        delete.enforcedTrace!!.entries.none { it.statement.document == "platform" }.assert().isTrue()
        plane.simulate(plato, bob("delete")).enforced.by!!.policy.assert().isEqualTo("no-delete")
        // The hard rule: another tenant's principal simulates nothing here.
        refused { plane.simulate(mallory, bob("read")) }.assert().isEqualTo(403)
        // A scope the principal is no member of is rejected, as the pipeline rejects it.
        plane.simulate(alice, bob("read").copy(roles = emptyList())).enforced.decision.assert().isEqualTo("Rejected")

        // A change's candidate, on the same request.
        val change = plane.propose(alice, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke")
        val simulated = plane.simulate(alice, bob("read"), change.id)
        simulated.candidate!!.decision.assert().isEqualTo("Deny")
        simulated.differs.assert().isTrue()
        val other = plane.propose(
            mallory,
            SnapshotKey.Tenant(GLOBEX.value),
            1,
            listOf(Operation.PutPolicy(allow("x"))),
            "theirs"
        )
        refused { plane.simulate(alice, bob("read"), other.id) }.assert().isEqualTo(404)
    }

    @Test
    fun `explain decides at the revisions an audit event recorded`() = runBlocking<Unit> {
        val change = plane.propose(alice, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke")
        plane.startShadow(alice, change.id)
        changes.shadowed(change.id)
        plane.publish(alice, change.id)
        plane.explain(alice, bob("read")).decision.decision.assert().isEqualTo("Deny")
        val then = plane.explain(alice, bob("read"), SnapshotVersion(1, null, 1))
        then.decision.decision.assert().isEqualTo("Permit")
        then.version.tenant.assert().isEqualTo(1L)
        refused { plane.explain(alice, bob("read"), SnapshotVersion(1, null, 7)) }.assert().isEqualTo(410)
        refused { plane.explain(mallory, bob("read")) }.assert().isEqualTo(403)
    }

    @Test
    fun `analyze lists what the caller may read, and counts the rest`() = runBlocking<Unit> {
        val query = AccessQuery(ACME, "order", "team-a", Action("order", "delete"), Resource("order", "1", emptyMap()))
        val forAlice = plane.analyze(alice, query)
        forAlice.allows.map { it.binding }.assert().containsExactly("ops")
        forAlice.allows.single().subject.assert().isEqualTo("role:acme/ops")
        forAlice.denies.assert().isEmpty()
        // The platform's document and the order app's: alice may read neither.
        forAlice.hidden.assert().isEqualTo(2)
        val forPlato = plane.analyze(plato, query)
        forPlato.denies.map { it.binding }.assert().containsExactly("no-delete")
        forPlato.hidden.assert().isEqualTo(0)
        refused { plane.analyze(mallory, query) }.assert().isEqualTo(403)
        plane.analyze(alice, query.copy(app = "billing")).allows.assert().isEmpty()
    }

    @Test
    fun `simulate refuses a candidate the request does not read, and explain rejects what the pipeline rejects`() =
        runBlocking<Unit> {
            val billing = plane.propose(
                plato,
                SnapshotKey.App("billing"),
                0,
                listOf(Operation.PutPolicy(allow("x"))),
                "billing"
            )
            refused { plane.simulate(plato, bob("read"), billing.id) }.assert().isEqualTo(400)
            plane.explain(alice, bob("read").copy(roles = emptyList())).decision.decision.assert().isEqualTo("Rejected")
            plane.explain(plato, bob("read"), SnapshotVersion(1, 0, 1)).decision.decision.assert().isEqualTo("Permit")
        }

    @Test
    fun `a binding to a policy in no readable document is counted as opaque`() = runBlocking<Unit> {
        val query = AccessQuery(
            ACME,
            PipelineControlAuthorizer.CONTROL_APP.value,
            SpaceId.DEFAULT.value,
            Action("cosec", "read"),
            Resource("cosec", "tenant/acme", emptyMap()),
        )
        plane.analyze(plato, query).opaque.assert().isGreaterThan(0)
        plane.analyze(plato, query.copy(app = null, space = null)).hidden.assert().isEqualTo(0)
    }

    @Test
    fun `the change steps go through the workflow`() = runBlocking<Unit> {
        fun put(id: String) = listOf(Operation.PutPolicy(allow(id)))
        val rejected = plane.propose(plato, TENANT, 1, put("a"), "a")
        plane.rebase(plato, rejected.id).id.assert().isEqualTo(rejected.id)
        refused { plane.reject(plato, rejected.id, "no") }.assert().isEqualTo(409)
        val abandoned = plane.propose(plato, TENANT, 1, put("b"), "b")
        plane.abandon(plato, abandoned.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        val skipped = plane.propose(plato, TENANT, 1, put("c"), "c")
        plane.skipShadow(plato, skipped.id, "test")
        refused { plane.approve(plato, skipped.id, "stale") }.assert().isIn(403, 409, 412)
        plane.publish(plato, skipped.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        plane.rollback(plato, TENANT, 1, "back").key.assert().isEqualTo(TENANT)
        plane.apply(plato, TENANT, plane.read(plato, TENANT), ApplyMode.MERGE, "same").key.assert().isEqualTo(TENANT)
    }

    companion object {
        val ACME = TenantId("acme")
        val GLOBEX = TenantId("globex")
        val TENANT: SnapshotKey = SnapshotKey.Tenant("acme")
        val ORDER = AppId("order")
    }
}
