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

package me.ahoo.cosec.control.authz

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 0007 §7.1–§7.5 through the real pipeline and engine: the built-in control-plane policies, the hard rule, the
 * checks of every target, the grant analysis on each change and the approvals it leads to. Each refused step leaves
 * the head where it was.
 */
class ControlAuthorizationTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private var failAppReads = false
    private val store = object : ControlStore by memory {
        override suspend fun head(key: SnapshotKey): StoredDocument? {
            check(!(failAppReads && key is SnapshotKey.App)) { "the store is down" }
            return memory.head(key)
        }
    }
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()

    private fun changes(
        options: ChangeOptions = ChangeOptions(shadow = IMMEDIATE_WINDOW),
        authorizer: ControlAuthorizer = engine.authorizer
    ) = Changes(store, revisions, { ChangeId("c${ids.incrementAndGet()}") }, authorizer, clock, options)

    private val changes = changes()

    private val plato = engine.operator(TenantId.PLATFORM, "plato")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")
    private val alice = engine.operator(ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val bob = engine.operator(ACME, "bob", roles = listOf("admin"))
    private val eve = engine.operator(ACME, "eve")
    private val mallory = engine.operator(GLOBEX, "mallory")

    init {
        runBlocking {
            bootstrap(
                SnapshotKey.Platform,
                emptyList(),
                listOf(
                    control(
                        "plato",
                        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
                        BuiltinPolicies.PLATFORM_ADMIN
                    ),
                    control(
                        "pia",
                        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("pia")),
                        BuiltinPolicies.PLATFORM_ADMIN
                    ),
                    // A policy written wrongly: a tenant's principal holding every control-plane action.
                    control(
                        "mallory",
                        SubjectRef.Principal(GLOBEX, PrincipalId("mallory")),
                        BuiltinPolicies.PLATFORM_ADMIN
                    ),
                ),
            )
            bootstrap(
                TENANT,
                listOf(allow("orders", action = "order:*", resource = "order/**")),
                listOf(
                    control("admins", SubjectRef.Role(ACME, RoleId("admin")), BuiltinPolicies.TENANT_ADMIN),
                    Binding(
                        BindingId("ops"),
                        SubjectRef.Role(ACME, RoleId("ops")),
                        BindingScope(Match.Exactly(ORDER), Match.Any),
                        PolicyId("orders")
                    ),
                ),
            )
        }
    }

    private suspend fun bootstrap(key: SnapshotKey, policies: List<PolicyDefinition>, bindings: List<Binding>) {
        val head = revisions.head(key)
        revisions.publish(
            key,
            head,
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

    /** Grants [action] on one order to the viewers of acme in [space] of the order app. */
    private fun grant(space: String?, policy: String = "viewer-read", action: String = "order:read") = listOf(
        Operation.PutPolicy(allow(policy, action = action, resource = "order/{id}")),
        Operation.PutBinding(
            Binding(
                BindingId(policy),
                SubjectRef.Role(ACME, RoleId("viewer")),
                BindingScope(Match.Exactly(ORDER), space?.let { Match.Exactly(SpaceId(it)) } ?: Match.Any),
                PolicyId(policy),
            ),
        ),
    )

    private suspend fun head(key: SnapshotKey = TENANT): Long = revisions.head(key)?.revision ?: 0

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    private suspend fun windowClosed(change: ChangeRequest, by: Operator = alice): ChangeRequest {
        changes.stage(by, change.id)
        return changes.shadowed(change.id)
    }

    @Test
    fun `a tenant admin publishes alone what it holds itself`() = runBlocking<Unit> {
        val change = changes.propose(alice, TENANT, 1, grant("team-a"), "viewers")
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        change.escalations.assert().isEmpty()
        // tenant-admin holds neither skip-shadow nor approve.
        refused { changes.skipShadow(alice, change.id, "hurry") }.assert().isEqualTo(403)
        windowClosed(change).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(alice, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        revisions.head(TENANT)!!.document.provenance!!.publishedBy.assert().isEqualTo(alice.actor)
    }

    @Test
    fun `what the analysis cannot prove waits for another person's approval of its digest`() = runBlocking<Unit> {
        val change = changes.propose(alice, TENANT, 1, grant(null), "viewers everywhere")
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        change.escalations.map { it.reason }.assert().containsExactly("NOT_COVERED")
        windowClosed(change).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        refused { changes.publish(alice, change.id) }.assert().isEqualTo(409)
        // The author, and an admin without cosec:approve, cannot approve; the right person approves what they saw.
        refused { changes.approve(alice, change.id, change.candidate!!) }.assert().isEqualTo(403)
        refused { changes.approve(bob, change.id, change.candidate!!) }.assert().isEqualTo(403)
        refused { changes.approve(plato, change.id, "0".repeat(64)) }.assert().isEqualTo(409)
        val approved = changes.approve(plato, change.id, change.candidate!!)
        approved.state.assert().isEqualTo(ChangeState.READY)
        approved.approval!!.approver.assert().isEqualTo(plato.actor)
        changes.get(change.id).approval.assert().isEqualTo(approved.approval)
        changes.publish(alice, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        revisions.head(TENANT)!!.document.provenance!!.approvedBy.assert().containsExactly(plato.actor)
    }

    @Test
    fun `an expired approval publishes nothing and asks again`() = runBlocking<Unit> {
        val change = windowClosed(changes.propose(alice, TENANT, 1, grant(null), "viewers everywhere"))
        changes.approve(plato, change.id, change.candidate!!)
        clock.now = clock.now.plus(Duration.ofHours(73))
        val outcome = changes.publish(alice, change.id)
        outcome.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        outcome.approval.assert().isNull()
        outcome.errors.map { it.check to it.status }.assert().contains("APPROVAL" to 428)
        head().assert().isEqualTo(1)
        changes.approve(pia, change.id, change.candidate!!)
        changes.publish(alice, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `a rejected change never publishes`() = runBlocking<Unit> {
        val change = windowClosed(changes.propose(alice, TENANT, 1, grant(null), "viewers everywhere"))
        refused { changes.reject(bob, change.id, "no") }.assert().isEqualTo(403)
        changes.reject(plato, change.id, "too wide").state.assert().isEqualTo(ChangeState.REJECTED)
        refused { changes.publish(alice, change.id) }.assert().isEqualTo(409)
        head().assert().isEqualTo(1)
        // The slot is free again.
        windowClosed(
            changes.propose(alice, TENANT, 1, grant("team-a"), "narrower")
        ).state.assert().isEqualTo(ChangeState.READY)
    }

    @Test
    fun `the hard rule stands whatever the policies say`() = runBlocking<Unit> {
        // mallory holds cosec:* on doc/** by a wrongly written binding, and still writes only globex's document.
        refused { changes.propose(mallory, TENANT, 1, grant("team-a"), "theirs") }.assert().isEqualTo(403)
        refused {
            changes.propose(mallory, SnapshotKey.Platform, 1, listOf(Operation.PutPolicy(allow("p"))), "platform")
        }.assert().isEqualTo(403)
        refused {
            changes.propose(alice, SnapshotKey.App("order"), 0, listOf(Operation.PutPolicy(allow("p"))), "app")
        }.assert().isEqualTo(403)
        changes.propose(mallory, SnapshotKey.Tenant(GLOBEX.value), 0, listOf(Operation.PutPolicy(allow("p"))), "own")
            .state.assert().isEqualTo(ChangeState.CHECKED)
        // Nor may a tenant's principal approve or publish another tenant's change: to them it does not exist (CTL-15).
        val change = windowClosed(changes.propose(alice, TENANT, 1, grant(null), "viewers everywhere"))
        refused { changes.approve(mallory, change.id, change.candidate!!) }.assert().isEqualTo(404)
        refused { changes.abandon(mallory, change.id) }.assert().isEqualTo(404)
        head().assert().isEqualTo(1)
    }

    @Test
    fun `nobody writes without the right, and nothing is recorded`() = runBlocking<Unit> {
        refused { changes.propose(eve, TENANT, 1, grant("team-a"), "eve") }.assert().isEqualTo(403)
        memory.change(ChangeId("c1")).assert().isNull()
        head().assert().isEqualTo(1)
    }

    @Test
    fun `an undecidable check, a failed confirmation and a failing analysis all refuse`() = runBlocking<Unit> {
        val indeterminate = changes(authorizer = { _, _, _ -> ControlDecision.INDETERMINATE })
        refused { indeterminate.propose(alice, TENANT, 1, grant("team-a"), "x") }.assert().isEqualTo(503)
        val throwing = changes(authorizer = { _, _, _ -> error("the checker is down") })
        refused { throwing.propose(alice, TENANT, 1, grant("team-a"), "x") }.assert().isEqualTo(503)
        engine.confirmation = DocumentConfirmation { error("the documents cannot be read") }
        refused { changes.propose(alice, TENANT, 1, grant("team-a"), "x") }.assert().isEqualTo(503)
        engine.confirmation = DocumentConfirmation.CURRENT
        failAppReads = true
        refused { changes.propose(alice, TENANT, 1, grant("team-a"), "x") }.assert().isEqualTo(503)
        failAppReads = false
        head().assert().isEqualTo(1)
    }

    @Test
    fun `publishing checks the publisher again on the current heads`() = runBlocking<Unit> {
        val change = windowClosed(changes.propose(alice, TENANT, 1, grant("team-a"), "viewers"))
        // The platform denies alice every control-plane action after she proposed.
        bootstrap(
            SnapshotKey.Platform,
            listOf(
                PolicyDefinition(
                    PolicyId("lock-alice"),
                    false,
                    null,
                    listOf(StatementSource("", Effect.DENY, listOf("cosec:*")))
                )
            ),
            revisions.head(SnapshotKey.Platform)!!.document.bindings +
                control("lock", SubjectRef.Principal(ACME, PrincipalId("alice")), PolicyId("lock-alice")),
        )
        refused { changes.publish(alice, change.id) }.assert().isEqualTo(403)
        changes.get(change.id).state.assert().isEqualTo(ChangeState.READY)
        head().assert().isEqualTo(1)
    }

    @Test
    fun `narrowing never needs an approval`() = runBlocking<Unit> {
        val change = changes.propose(alice, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke")
        change.escalations.assert().isEmpty()
        windowClosed(change).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(alice, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `the trust root publishes alone unless four-eyes is on`() = runBlocking<Unit> {
        val alone = changes.propose(plato, TENANT, 1, grant(null), "wide")
        alone.escalations.assert().isEmpty()
        windowClosed(alone, plato).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(plato, alone.id).state.assert().isEqualTo(ChangeState.PUBLISHED)

        val fourEyes = changes(ChangeOptions(fourEyes = true, shadow = IMMEDIATE_WINDOW))
        val change = fourEyes.propose(plato, TENANT, 2, grant("team-a", "deleter", "order:delete"), "more")
        change.escalations.assert().isNotEmpty()
        fourEyes.stage(plato, change.id)
        fourEyes.shadowed(change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        refused { fourEyes.approve(plato, change.id, change.candidate!!) }.assert().isEqualTo(403)
        fourEyes.approve(pia, change.id, change.candidate!!)
        // An approval does not let its approver publish alone.
        fourEyes.publish(pia, change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        fourEyes.approve(pia, change.id, change.candidate!!)
        fourEyes.publish(plato, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `a platform or app change by anyone but the trust root needs a second person`() = runBlocking<Unit> {
        // opal may write the app documents, but holds no cosec:approve: what she puts is not analyzed, it is approved.
        val writer = PolicyDefinition(
            PolicyId("app-writer"),
            false,
            null,
            listOf(
                StatementSource(
                    "",
                    Effect.ALLOW,
                    listOf("cosec:write", "cosec:grant", "cosec:publish"),
                    listOf(ResourceSource("doc/app/**"), ResourceSource("scope/**"))
                )
            ),
        )
        bootstrap(
            SnapshotKey.Platform,
            listOf(writer),
            revisions.head(SnapshotKey.Platform)!!.document.bindings +
                control("opal", SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("opal")), PolicyId("app-writer")),
        )
        val opal = engine.operator(TenantId.PLATFORM, "opal")
        val order = SnapshotKey.App("order")
        val change = changes.propose(opal, order, 0, listOf(Operation.PutPolicy(allow("perm:order/read"))), "app")
        change.escalations.map { it.reason }.assert().containsExactly("NOT_ANALYZED")
        windowClosed(change, opal).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        changes.approve(plato, change.id, change.candidate!!)
        changes.publish(opal, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        // Removing grants nothing.
        val removal = changes.propose(
            opal,
            order,
            1,
            listOf(Operation.RemovePolicy(PolicyId("perm:order/read"))),
            "narrow"
        )
        removal.escalations.assert().isEmpty()
    }

    /** RFC 0007 §7.6: only the platform sets a boundary, and anyone but the trust root needs a second person. */
    @Test
    fun `a boundary is the platform's to set`() = runBlocking<Unit> {
        val fence = PolicyDefinition(
            PolicyId("fence"),
            false,
            null,
            listOf(StatementSource("", Effect.ALLOW, listOf("order:*"), emptyList())),
            boundary = true,
        )
        val setter = PolicyDefinition(
            PolicyId("boundary-setter"),
            false,
            null,
            listOf(
                StatementSource("", Effect.ALLOW, listOf("cosec:boundary"), listOf(ResourceSource("doc/tenant/**")))
            ),
        )
        bootstrap(
            SnapshotKey.Platform,
            listOf(fence, setter),
            revisions.head(SnapshotKey.Platform)!!.document.bindings +
                control("bea", SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("bea")), PolicyId("boundary-setter")),
        )
        val set = listOf(Operation.SetBoundary(PolicyId("fence")))
        // A tenant admin cannot loosen or tighten its own tenant's boundary.
        refused { changes.propose(alice, TENANT, head(), set, "mine") }.assert().isEqualTo(403)
        val bea = engine.operator(TenantId.PLATFORM, "bea")
        changes.propose(bea, TENANT, head(), set, "fence acme").escalations.map { it.reason }.assert()
            .containsExactly("NOT_ANALYZED")
        changes.propose(plato, TENANT, head(), set, "trust root").escalations.assert().isEmpty()
    }

    @Test
    fun `resources name one document each`() {
        ControlResources.document(SnapshotKey.Platform).id.assert().isEqualTo("platform")
        ControlResources.entry(TENANT, "policy", "p").id.assert().isEqualTo("tenant/acme/policy/p")
        ControlResources.entry(SnapshotKey.App("order"), "catalog", "").id.assert().isEqualTo("app/order/catalog")
        ControlResources.scope(null, Match.Any, Match.Exactly(SpaceId.DEFAULT)).id.assert().isEqualTo("*/*/-")
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ControlResources.document(
                SnapshotKey.Tenant("a/b")
            )
        }
    }

    private companion object {
        val ACME = TenantId("acme")
        val GLOBEX = TenantId("globex")
        val TENANT: SnapshotKey = SnapshotKey.Tenant("acme")
        val ORDER = AppId("order")
    }
}
