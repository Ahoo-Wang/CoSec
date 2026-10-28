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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * RFC 0007 §6.6: whatever keeps a change from publishing (a check, a moved base, a held slot, a failing store) leaves
 * the head as it was. Failing is never publishing.
 */
class ControlFailClosedTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private var failHead = false
    private var failChanges = false
    private val store = object : ControlStore by memory {
        override suspend fun putHead(
            key: SnapshotKey,
            expected: SnapshotDigest?,
            document: SnapshotDocument
        ): HeadWrite {
            check(!failHead) { "the store is down" }
            return memory.putHead(key, expected, document)
        }

        override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean {
            check(!failChanges) { "the store is down" }
            return memory.putChange(expected, change)
        }
    }
    private val harness = ChangeHarness(clock, memory, store)
    private val changes = harness.changes

    private suspend fun ready(vararg operations: Operation, base: Long = 0): ChangeRequest {
        val change = changes.propose(ALICE_OP, ACME, base, operations.toList(), "test")
        return changes.skipShadow(ALICE_OP, change.id, "test")
    }

    private suspend fun head() = harness.revisions.head(ACME)

    @Test
    fun `a moved base conflicts and publishes nothing, a rebase checks again`() = runBlocking<Unit> {
        val first = ready(Operation.PutPolicy(allow("first")))
        val second = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("second"))), "late")
        changes.publish(ALICE_OP, first.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        // The slot is free again, so the second can stage; it publishes on a base that is gone.
        changes.skipShadow(ALICE_OP, second.id, "test")
        val conflicted = changes.publish(ALICE_OP, second.id)
        conflicted.state.assert().isEqualTo(ChangeState.CONFLICTED)
        head()!!.document.policies.map { it.id.value }.assert().containsExactly("first")
        val rebased = changes.rebase(ALICE_OP, second.id)
        rebased.state.assert().isEqualTo(ChangeState.CHECKED)
        rebased.base.revision.assert().isEqualTo(1)
        rebased.candidate.assert().isNotEqualTo(second.candidate)
        changes.skipShadow(ALICE_OP, second.id, "test")
        changes.publish(ALICE_OP, second.id).publishedRevision.assert().isEqualTo(2)
        head()!!.document.policies.map { it.id.value }.assert().containsExactly("first", "second")
    }

    @Test
    fun `proposing on a revision that is not the head is refused`() = runBlocking<Unit> {
        changes.publish(ALICE_OP, ready(Operation.PutPolicy(allow("a"))).id)
        assertThrownBy<ChangeRejectedException> {
            runBlocking { changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("b"))), "stale") }
        }.extracting { (it as ChangeRejectedException).status }.isEqualTo(409)
    }

    @Test
    fun `an ifMatch that no longer holds fails the rebase instead of overwriting`() = runBlocking<Unit> {
        changes.publish(ALICE_OP, ready(Operation.PutPolicy(allow("p", action = "order:read"))).id)
        val seen = EntryDigest.of(head()!!.document.policies.single())
        val mine = changes.propose(
            ALICE_OP,
            ACME,
            1,
            listOf(Operation.PutPolicy(allow("p", action = "order:list"), seen)),
            "mine"
        )
        changes.publish(ALICE_OP, ready(Operation.PutPolicy(allow("p", action = "order:ship"), seen), base = 1).id)
        val rebased = changes.rebase(ALICE_OP, mine.id)
        rebased.state.assert().isEqualTo(ChangeState.DRAFT)
        rebased.errors.single().status.assert().isEqualTo(412)
        head()!!.document.policies.single().statements.single().actions.assert().containsExactly("order:ship")
    }

    @Test
    fun `a failing store publishes nothing and keeps the change ready`() = runBlocking<Unit> {
        val change = ready(Operation.PutPolicy(allow("a")))
        failHead = true
        assertThrownBy<IllegalStateException> { runBlocking { changes.publish(ALICE_OP, change.id) } }
        failHead = false
        head().assert().isNull()
        changes.get(change.id).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(ALICE_OP, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `a change that cannot be recorded is never checked or staged`() = runBlocking<Unit> {
        failChanges = true
        assertThrownBy<IllegalStateException> {
            runBlocking { changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("a"))), "lost") }
        }
        failChanges = false
        memory.stage(ACME).assert().isNull()
        head().assert().isNull()
    }

    @Test
    fun `one staged change per document`() = runBlocking<Unit> {
        val first = ready(Operation.PutPolicy(allow("a")))
        val second = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("b"))), "second")
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.stage(ALICE_OP, second.id) } }
            .extracting { (it as ChangeRejectedException).status }.isEqualTo(409)
        changes.abandon(ALICE_OP, first.id).state.assert().isEqualTo(ChangeState.ABANDONED)
        changes.stage(ALICE_OP, second.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        changes.shadowed(second.id).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(ALICE_OP, second.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        memory.stage(ACME)!!.value.holder.assert().isNull()
    }

    @Test
    fun `an expired change frees its slot and publishes nothing`() = runBlocking<Unit> {
        val stale = ready(Operation.PutPolicy(allow("a")))
        clock.now = clock.now.plus(ChangeOptions().expireAfter).plusSeconds(1)
        changes.get(stale.id).state.assert().isEqualTo(ChangeState.EXPIRED)
        assertThrownBy<ChangeRejectedException> { runBlocking { changes.publish(ALICE_OP, stale.id) } }
        val next = changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("b"))), "next")
        changes.stage(ALICE_OP, next.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        head().assert().isNull()
    }
}
