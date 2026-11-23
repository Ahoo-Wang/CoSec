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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ALICE_OP
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ConflictKind
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.NO_MEMBERSHIPS
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** `CTL-83` (DR1-E-2) as the change flow applies it: the open-change limit, what makes room, and the ended changes' edges. */
class OpenLimitTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val ids = AtomicInteger()
    private val bob = Operator(Actor(TenantId("acme"), PrincipalId("bob")), NO_MEMBERSHIPS)

    private fun changes(over: ControlStore = store, maxCount: Int = 2) = Changes(
        over,
        Revisions(over, clock, RetentionPolicy(maxCount = maxCount)),
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
    )

    private suspend fun published() {
        Revisions(store, clock).publish(ACME, null, candidate(ACME), publication())
    }

    private suspend fun Changes.open(by: Operator = ALICE_OP): ChangeRequest {
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        return propose(by, ACME, 1, listOf(Operation.PutPolicy(allow("read"))), "open")
    }

    /** A change whose checks fail: a binding to a policy that is not there, which does not compile. */
    private suspend fun Changes.failed(by: Operator = ALICE_OP): ChangeRequest {
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        val binding =
            Binding(
                BindingId("b${ids.get()}"),
                SubjectRef.Anyone,
                BindingScope(Match.Any, Match.Any),
                PolicyId("nowhere")
            )
        return propose(by, ACME, 1, listOf(Operation.PutBinding(binding)), "failed").also {
            it.state.assert().isEqualTo(ChangeState.DRAFT)
        }
    }

    private suspend fun refused(block: suspend () -> Unit): ChangeRejectedException = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e
    }

    @Test
    fun `at the limit a change is refused OPEN_LIMIT, naming the oldest open change`() = runBlocking<Unit> {
        published()
        val changes = changes()
        val oldest = changes.open()
        changes.open(bob)

        val refused = refused { changes.open() }

        refused.status.assert().isEqualTo(409)
        refused.conflict.assert().isEqualTo(ConflictKind.OPEN_LIMIT)
        refused.heldBy.assert().isEqualTo(oldest.id)
        store.allOpenChanges().size.assert().isEqualTo(2)
    }

    @Test
    fun `the author's own oldest failed draft makes room, never another author's`() = runBlocking<Unit> {
        published()
        val changes = changes()
        val draft = changes.failed()
        changes.failed(bob)

        val stored = changes.open()

        stored.state.assert().isEqualTo(ChangeState.CHECKED)
        store.change(draft.id)!!.value.state.assert().isEqualTo(ChangeState.ABANDONED)
        // Its record keeps its note; the audit event of the abandon says it was for room (CTL-83).
        // Bob's failed draft is his: alice cannot make room with it.
        refused { changes.open() }.conflict.assert().isEqualTo(ConflictKind.OPEN_LIMIT)
    }

    @Test
    fun `a change that does not read counts as open`() = runBlocking<Unit> {
        published()
        val unreadable = object : ControlStore by store {
            override suspend fun openChanges(document: SnapshotKey): List<ListedChange> =
                store.openChanges(document) + ListedChange.Unreadable(ChangeId("garbled"), document)
        }
        val changes = changes(unreadable)
        changes.open()

        refused { changes.open() }.conflict.assert().isEqualTo(ConflictKind.OPEN_LIMIT)
    }

    @Test
    fun `at the limit with nothing that reads the refusal names no change`() = runBlocking<Unit> {
        published()
        val unreadable = object : ControlStore by store {
            override suspend fun openChanges(document: SnapshotKey): List<ListedChange> =
                listOf(
                    ListedChange.Unreadable(ChangeId("x"), document),
                    ListedChange.Unreadable(ChangeId("y"), document)
                )
        }

        val refused = refused { changes(unreadable).open() }

        refused.conflict.assert().isEqualTo(ConflictKind.OPEN_LIMIT)
        refused.heldBy.assert().isNull()
    }

    @Test
    fun `a draft another write moved first is not abandoned for room, and the change is refused`() = runBlocking<Unit> {
        published()
        val racing = object : ControlStore by store {
            override suspend fun putChange(
                expected: me.ahoo.cosec.spi.snapshot.SnapshotDigest?,
                change: ChangeRequest,
                previous: ChangeState?,
            ): Boolean = if (change.state == ChangeState.ABANDONED) {
                false
            } else {
                store.putChange(
                    expected,
                    change,
                    previous
                )
            }
        }
        val changes = changes(racing)
        changes.failed()
        changes.open(bob)

        refused { changes.open() }.conflict.assert().isEqualTo(ConflictKind.OPEN_LIMIT)
    }

    @Test
    fun `an ended change whose record does not read, or is not terminal, stays listed`() = runBlocking<Unit> {
        published()
        val first = changes(maxCount = 50).open()
        changes(maxCount = 50).abandon(ALICE_OP, first.id)
        val garbled = object : ControlStore by store {
            override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? =
                if (id == first.id) throw SnapshotFormatException("garbled") else store.change(id)
        }
        val lister = me.ahoo.cosec.control.lifecycle.EndedChanges(garbled, maxCount = 0)

        lister.bound(ACME)

        store.change(first.id).assert().isNotNull()
        store.endedChanges(ACME, Instant.MAX, Int.MAX_VALUE).map { it.id }.assert().containsExactly(first.id)
    }

    @Test
    fun `an ended change is a value`() {
        val at = Instant.EPOCH
        EndedChange(ChangeId("a"), at).assert().isEqualTo(EndedChange(ChangeId("a"), at))
        EndedChange(ChangeId("a"), at).hashCode().assert().isEqualTo(EndedChange(ChangeId("a"), at).hashCode())
        EndedChange(ChangeId("a"), at).assert().isNotEqualTo(EndedChange(ChangeId("b"), at))
        EndedChange(ChangeId("a"), at).toString().assert().isEqualTo("EndedChange(id=a, endedAt=1970-01-01T00:00:00Z)")
    }
}
