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
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-43`: the platform's checks read the flag each back-reference entry carries, never a tenant's head. A removal is
 * blocked by a referrer unless every entry of it says it defines the id itself; an addition is blocked by any entry that
 * says so. An entry a later revision or a lost race left behind errs towards blocking in both: a stale entry that refers
 * blocks a removal, a stale entry that defines blocks an addition, and neither ever lets a change through.
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
    fun `an addition is blocked by any entry that defines the id, and not by one that only refers to it`() =
        runBlocking<Unit> {
            publish(ACME, Operation.PutPolicy(allow("editor")))
            harness.memory.referrers(
                platform,
                PolicyId("editor")
            ).assert().containsExactly(ReferrerEntry(ACME, 1, true))
            adding("editor").errors.map { it.check to it.status }.assert().containsExactly("V2" to 422)
            // An entry that only refers, such as a stale one, is no use of the id: the platform may define it.
            harness.memory.addReferrer(platform, PolicyId("viewer"), ReferrerEntry(beta, 3))
            adding("viewer").errors.assert().isEmpty()
        }

    @Test
    fun `a stale entry that defines the id still blocks an addition`() = runBlocking<Unit> {
        publish(ACME, Operation.PutPolicy(allow("editor")))
        publish(ACME, Operation.RemovePolicy(PolicyId("editor")), Operation.PutPolicy(allow("other")))
        // As a lost race leaves it: acme no longer defines editor, and an entry of revision 1 still says it does.
        harness.memory.addReferrer(platform, PolicyId("editor"), ReferrerEntry(ACME, 1, true))
        adding("editor").errors.map { it.check to it.status }.assert().containsExactly("V2" to 422)
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
}
