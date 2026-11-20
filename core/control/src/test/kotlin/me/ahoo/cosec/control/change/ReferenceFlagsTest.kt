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
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-43`: a removal of a platform policy is blocked by a referrer unless every entry of it says it defines the id
 * itself, read from the entries, never a head; a stale entry that refers errs towards blocking. An addition is blocked by
 * every tenant with an entry, defining or binding the id, confirmed by reading its head, so a stale entry does not
 * block it and a tenant whose head cannot be read does (503).
 */
class ReferenceFlagsTest {
    private val harness = ChangeHarness(options = ChangeOptions(shadow = IMMEDIATE_WINDOW))
    private val changes = harness.changes
    private val platform = SnapshotKey.Platform
    private val beta = SnapshotKey.Tenant("beta")

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation) {
        val head = harness.revisions.head(key)?.revision ?: 0
        val change = changes.propose(operatorFor(key), key, head, operations.toList(), "test")
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        changes.publish(operatorFor(key), change.id)
    }

    private suspend fun proposed(operation: Operation): ChangeRequest {
        val head = harness.revisions.head(platform)?.revision ?: 0
        return changes.propose(PLATO, platform, head, listOf(operation), "platform")
    }

    private suspend fun adding(id: String) = proposed(Operation.PutPolicy(allow(id)))

    private suspend fun removing(id: String) = proposed(Operation.RemovePolicy(PolicyId(id)))

    @Test
    fun `an addition is blocked by a tenant that defines the id`() = runBlocking<Unit> {
        publish(ACME, Operation.PutPolicy(allow("editor")))
        harness.memory.referrers(platform, PolicyId("editor")).assert().containsExactly(ReferrerEntry(ACME, 1, true))
        adding("editor").errors.map { it.check to it.status }.assert().containsExactly("V2" to 422)
    }

    /**
     * A tenant that binds an id it does not define, which no compiled change can write but a head written as
     * `cosec-migrate` writes can hold: a platform policy of that id would take the binding over, since the engine
     * resolves a binding without a policy of its own document to the platform's. So the addition is refused, at the
     * check and again at publish, which checks the change again.
     */
    @Test
    fun `an addition is blocked by a tenant that binds the id, at the check and at publish`() = runBlocking<Unit> {
        val pending = adding("shared")
        pending.errors.assert().isEmpty()
        changes.skipShadow(PLATO, pending.id, "test")
        harness.revisions.publish(
            beta,
            null,
            StoreFixtures.candidate(beta, bindings = listOf(bind("staff", "shared"))),
            StoreFixtures.publication(),
        ).assert().isInstanceOf(PublishOutcome.Published::class.java)
        harness.memory.referrers(platform, PolicyId("shared")).assert().containsExactly(ReferrerEntry(beta, 1))

        adding("shared").errors.map { it.check to it.message }.assert()
            .containsExactly("V2" to "[shared] is a policy id [tenant:beta] already use")
        val published = changes.publish(PLATO, pending.id)
        published.errors.map { it.check }.assert().contains("V2")
        harness.revisions.head(platform).assert().isNull()
    }

    /** An entry is a candidate, confirmed by its tenant's head: one a lost race left, whatever its flag, does not block. */
    @Test
    fun `a stale entry does not block an addition, since the head is read to confirm it`() = runBlocking<Unit> {
        publish(ACME, Operation.PutPolicy(allow("other")))
        harness.memory.addReferrer(platform, PolicyId("editor"), ReferrerEntry(ACME, 1, true))
        harness.memory.addReferrer(platform, PolicyId("editor"), ReferrerEntry(beta, 3))
        adding("editor").errors.assert().isEmpty()
    }

    @Test
    fun `a removal is blocked unless every entry of a referrer defines the id`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(allow("editor")), Operation.PutPolicy(allow("other")))
        publish(ACME, Operation.PutPolicy(allow("editor")))
        removing("editor").errors.assert().isEmpty()
        // A stale entry that refers, beside the one that defines: acme may still refer to the platform's.
        harness.memory.addReferrer(platform, PolicyId("editor"), ReferrerEntry(ACME, 1))
        removing("editor").errors.map { it.check to it.message }.assert()
            .containsExactly("REFERENCES" to "[editor] is referred to by [tenant:acme]")
        harness.revisions.publish(
            platform,
            harness.revisions.head(platform),
            StoreFixtures.candidate(platform, listOf(allow("other"))),
            StoreFixtures.publication(),
        ).assert().isInstanceOf(PublishOutcome.Referenced::class.java)
    }

    /** `CTL-42`: a platform policy whose back-references cannot be read may be copied by a tenant: changing it waits (503). */
    @Test
    fun `a platform policy whose back-references cannot be read is not changed`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(allow("editor")))
        val store = object : ControlStore by harness.memory {
            override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry> =
                error("[${policy.value}] cannot be read")
        }
        val changes = Changes(
            store,
            Revisions(store, harness.clock),
            { ChangeId("unreadable") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
            revocations = RevocationReader.NONE,
            lockout = LockoutCheck.UNCHECKED,
        )
        changes.propose(PLATO, platform, 1, listOf(Operation.PutPolicy(allow("editor", action = "order:*"))), "change")
            .errors.map { it.check to it.status }.assert().containsExactly("V2" to 503)
    }
}
