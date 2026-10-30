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
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * RC review batch B2 through the change flow: a narrowing change takes a busy staging slot (RC1-12), a change an earlier
 * publish wrote is settled as published, Revisions' step after a write runs once, and the app list is reconciled
 * before it is trusted (B1 review).
 */
class ChangesB2Test {
    private val harness = ChangeHarness(options = ChangeOptions(shadow = IMMEDIATE_WINDOW))
    private val changes = harness.changes

    private fun deny(id: String) = PolicyDefinition(
        PolicyId(id),
        attachable = false,
        condition = null,
        statements = listOf(StatementSource("", Effect.DENY, listOf("order:delete"))),
    )

    private fun attachable(policy: PolicyDefinition) =
        PolicyDefinition(policy.id, true, policy.condition, policy.statements, policy.boundary)

    private fun binding(id: String, policy: PolicyId) =
        Binding(BindingId(id), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), policy)

    private val base = StoreFixtures.candidate(
        SnapshotKey.Platform,
        listOf(allow("reads"), deny("no-delete"), attachable(allow("lendable")), attachable(deny("lendable-deny"))),
        listOf(
            binding("reads", PolicyId("reads")),
            binding("no-delete", PolicyId("no-delete")),
            binding("blocked", BuiltinPolicies.BLACKLIST),
        ),
    )

    private fun narrows(vararg operations: Operation): Boolean = runBlocking {
        val applied = Operations.apply(SnapshotKey.Platform, base, operations.toList()).candidate!!
        Narrowing.only(base, applied, operations.toList()) { null }
    }

    /** RC1-12: each kind of change the whitelist names narrows, and its widening twin does not. */
    @Test
    fun `only a whitelist of changes narrows`() {
        // 1. Removing a binding of ALLOWs narrows; removing one of a DENY, or a blacklist entry, widens.
        narrows(Operation.RemoveBinding(BindingId("reads"))).assert().isTrue()
        narrows(Operation.RemoveBinding(BindingId("no-delete"))).assert().isFalse()
        narrows(Operation.RemoveBinding(BindingId("blocked"))).assert().isFalse()
        // 2. An attachable policy of ALLOWs no longer attachable narrows; one of DENYs widens; a changed one is not it.
        narrows(Operation.PutPolicy(allow("lendable"))).assert().isTrue()
        narrows(Operation.PutPolicy(deny("lendable-deny"))).assert().isFalse()
        narrows(Operation.PutPolicy(allow("lendable", action = "order:*"))).assert().isFalse()
        // 3. A new blacklist entry narrows; one replacing a binding there is does not.
        narrows(Operation.PutBinding(binding("ban", BuiltinPolicies.BLACKLIST))).assert().isTrue()
        narrows(Operation.PutBinding(binding("reads", BuiltinPolicies.BLACKLIST))).assert().isFalse()
        // 4. A new policy of DENYs and a new binding of it narrow; an ALLOW, or a replaced DENY, does not.
        narrows(
            Operation.PutPolicy(deny("ban")),
            Operation.PutBinding(binding("ban", PolicyId("ban")))
        ).assert().isTrue()
        narrows(Operation.PutPolicy(allow("more"))).assert().isFalse()
        narrows(Operation.PutPolicy(deny("no-delete"))).assert().isFalse()
        narrows(Operation.PutBinding(binding("more", PolicyId("reads")))).assert().isFalse()
        // Anything else, and a mix with anything else, does not.
        narrows(Operation.RemovePolicy(PolicyId("reads"))).assert().isFalse()
        narrows(Operation.RemoveBinding(BindingId("reads")), Operation.PutPolicy(allow("more"))).assert().isFalse()
        narrows().assert().isFalse()
    }

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation) {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(PLATO, key, head, operations.toList(), "setup")
        changes.skipShadow(PLATO, change.id, "setup")
        changes.publish(PLATO, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    /** A platform change in its shadow window, holding the platform document's slot. */
    private suspend fun holding(changes: Changes = this.changes): ChangeRequest {
        val head = harness.revisions.head(SnapshotKey.Platform)!!.revision
        val change = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head,
            listOf(Operation.PutPolicy(allow("slow"))),
            "slow"
        )
        return changes.stage(PLATO, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }
    }

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    /** RC1-12 (`CTL`): an emergency ban takes the slot; anything wider, or anyone without skip-shadow, waits. */
    @Test
    fun `an emergency deny takes a busy slot, a widening change does not`() = runBlocking<Unit> {
        publish(
            SnapshotKey.Platform,
            Operation.PutPolicy(allow("reads")),
            Operation.PutBinding(binding("reads", PolicyId("reads")))
        )
        val held = holding()
        val head = harness.revisions.head(SnapshotKey.Platform)!!.revision
        val wider = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head,
            listOf(Operation.PutPolicy(allow("more"))),
            "more"
        )
        refused { changes.skipShadow(PLATO, wider.id, "hurry") }.assert().isEqualTo(409)
        changes.get(held.id).state.assert().isEqualTo(ChangeState.SHADOWING)

        // Without cosec:skip-shadow, not even a ban preempts.
        val unskippable = Changes(
            harness.memory,
            harness.revisions,
            { ChangeId("u${counter.incrementAndGet()}") },
            ControlAuthorizer { _, action, _ ->
                if (action.verb == "skip-shadow") ControlDecision.DENY else ControlDecision.PERMIT
            },
            harness.clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
        )
        val ban = listOf(Operation.PutPolicy(deny("ban")), Operation.PutBinding(binding("ban", PolicyId("ban"))))
        val staged = unskippable.propose(PLATO, SnapshotKey.Platform, head, ban, "ban")
        refused { unskippable.stage(PLATO, staged.id) }.assert().isEqualTo(409)

        val emergency = changes.propose(PLATO, SnapshotKey.Platform, head, ban, "ban")
        changes.skipShadow(PLATO, emergency.id, "incident").state.assert().isEqualTo(ChangeState.READY)
        changes.get(held.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        changes.publish(PLATO, emergency.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        harness.revisions.head(SnapshotKey.Platform)!!.document.bindings.map { it.id.value }.assert().contains("ban")
    }

    private val counter = AtomicInteger()

    /**
     * B1 review: a publish that wrote the head and could not settle the change leaves it READY; publishing it again finds
     * the head its own (the provenance names the change and its base), so it is PUBLISHED, not CONFLICTED.
     */
    @Test
    fun `a change an earlier publish wrote is published when published again`() = runBlocking<Unit> {
        var failing = false
        val store = object : ControlStore by harness.memory {
            override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean {
                check(!(failing && change.state == ChangeState.PUBLISHED)) { "Redis is down" }
                return harness.memory.putChange(expected, change)
            }
        }
        val flaky = Changes(
            store,
            Revisions(store, harness.clock),
            { ChangeId("f${counter.incrementAndGet()}") },
            PERMIT_ALL,
            harness.clock
        )
        val change = flaky.propose(PLATO, ACME, 0, listOf(Operation.PutPolicy(allow("reads"))), "reads")
        flaky.skipShadow(PLATO, change.id, "test")
        failing = true
        val failure = try {
            flaky.publish(PLATO, change.id)
            null
        } catch (e: IllegalStateException) {
            e
        }
        failure.assert().isNotNull()
        flaky.get(change.id).state.assert().isEqualTo(ChangeState.READY)
        failing = false
        val again = flaky.publish(PLATO, change.id)
        again.state.assert().isEqualTo(ChangeState.PUBLISHED)
        again.publishedRevision.assert().isEqualTo(1L)
        harness.revisions.head(ACME)!!.revision.assert().isEqualTo(1L)
    }

    /** B1 review: what follows Revisions' head write runs once, whatever its own steps threw. */
    @Test
    fun `the step after a head write runs exactly once`() = runBlocking<Unit> {
        var written = false
        val store = object : ControlStore by harness.memory {
            override suspend fun putHead(
                key: SnapshotKey,
                expected: SnapshotDigest?,
                document: me.ahoo.cosec.spi.snapshot.SnapshotDocument
            ) =
                harness.memory.putHead(key, expected, document).also { written = true }

            override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: me.ahoo.cosec.control.store.RevisionIndex): Boolean {
                if (written) throw AssertionError("an Error, which settling does not catch")
                return harness.memory.putIndex(key, expected, index)
            }
        }
        val after = AtomicInteger()
        val error = try {
            Revisions(
                store,
                harness.clock
            ).publish(ACME, null, StoreFixtures.candidate(ACME), StoreFixtures.publication()) {
                after.incrementAndGet()
            }
            null
        } catch (e: AssertionError) {
            e
        }
        error.assert().isNotNull()
        after.get().assert().isEqualTo(1)
    }

    /**
     * B1 review: until this instance has reconciled the app list once, the analysis does it before trusting the list:
     * a complete list an older writer left missing an app is not trusted on an instance that just started.
     */
    @Test
    fun `the first analysis reconciles the app list before it trusts it`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, Operation.PutPolicy(allow("reads")))
        // An older writer publishes billing without listing it: the list still says complete.
        val older = object : ControlStore by harness.memory {
            override suspend fun apps() = null

            override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean = true
        }
        val billing = SnapshotKey.App("billing")
        Revisions(
            older,
            harness.clock
        ).publish(billing, null, StoreFixtures.candidate(billing), StoreFixtures.publication())
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
        harness.memory.apps()!!.value.complete.assert().isTrue()

        val started = Changes(
            harness.memory,
            harness.revisions,
            { ChangeId("s${counter.incrementAndGet()}") },
            PERMIT_ALL,
            harness.clock
        )
        started.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("acme-reads"))), "first")
            .errors.assert().isEmpty()
        harness.memory.apps()!!.value.complete.assert().isFalse()
    }
}
