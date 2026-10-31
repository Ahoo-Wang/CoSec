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
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review item 13: a publish whose head write went through but whose reply was lost is recognised when it is retried,
 * by its own revision on the chain, the head or one since replaced, and finished as the write would have finished it:
 * settled, audited as the publisher the revision records, and what moved meanwhile recorded. Until then no change takes
 * its slot.
 */
class RetriedPublishTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** Whether the next head write goes through and then fails, as a reply lost on the way back. */
    @Volatile
    private var loseReply = false
    private val store = object : ControlStore by memory {
        override suspend fun putHead(
            key: SnapshotKey,
            expected: SnapshotDigest?,
            document: SnapshotDocument
        ): HeadWrite {
            val written = memory.putHead(key, expected, document)
            if (loseReply) {
                loseReply = false
                error("the reply was lost")
            }
            return written
        }
    }
    private val revisions = Revisions(store, clock)
    private val events = CopyOnWriteArrayList<ChangeEvent>()
    private val listeners = ChangeListeners(
        listOf(
            object : ChangeListener {
                override val name = "recorder"
                override val delivery = Delivery.REQUIRED

                override suspend fun onChange(event: ChangeEvent) {
                    events += event
                }
            }
        )
    )
    private val ids = AtomicInteger()
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        listeners = listeners,
    )

    private suspend fun head(key: SnapshotKey): Long = revisions.head(key)?.revision ?: 0

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    /** [change] READY, and published with its reply lost: the head is its revision, the change still READY. */
    private suspend fun lost(change: ChangeRequest): ChangeRequest {
        changes.skipShadow(PLATO, change.id, "test")
        loseReply = true
        val failure = try {
            changes.publish(PLATO, change.id)
            null
        } catch (e: IllegalStateException) {
            e
        }
        failure.assert().isNotNull()
        return changes.get(change.id).also { it.state.assert().isEqualTo(ChangeState.READY) }
    }

    @Test
    fun `a retry after a lost reply finishes the publish as its publisher`() = runBlocking<Unit> {
        val change = changes.propose(PLATO, ACME, 0, listOf(Operation.PutPolicy(allow("reads"))), "reads")
        lost(change)
        revisions.head(ACME)!!.revision.assert().isEqualTo(1L)
        // Published, though READY: a ban waits for the retry instead of conflicting the change that is in effect.
        val ban = changes.propose(PLATO, ACME, 1, BAN, "ban")
        refused { changes.skipShadow(PLATO, ban.id, "incident") }.assert().isEqualTo(409)
        // What the change was checked with moves before the retry: what moved is recorded.
        revisions.publish(
            SnapshotKey.Platform,
            null,
            StoreFixtures.candidate(SnapshotKey.Platform, listOf(allow("platform")), emptyList()),
            StoreFixtures.publication("bootstrap")
        )
        events.clear()
        val again = changes.publish(PIA, change.id)
        again.state.assert().isEqualTo(ChangeState.PUBLISHED)
        again.publishedRevision.assert().isEqualTo(1L)
        // Events that do not gate a step reach every listener on its own queue: wait for them.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (events.none { it.kind == ChangeEvent.Kind.PUBLISHED } ||
            events.none { it.kind == ChangeEvent.Kind.HEADS_MOVED || it.kind == ChangeEvent.Kind.REVIEW_NEEDED }
        ) {
            check(System.nanoTime() < deadline) { "Not delivered: ${events.map { it.kind }}" }
            Thread.sleep(10)
        }
        events.single { it.kind == ChangeEvent.Kind.PUBLISHED }.operator.assert().isEqualTo(PLATO.actor)
        events.filter { it.kind != ChangeEvent.Kind.PUBLISHED }.forEach { it.operator.assert().isEqualTo(PLATO.actor) }
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L)
        changes.skipShadow(PLATO, ban.id, "incident").state.assert().isEqualTo(ChangeState.READY)
        listeners.cancel()
    }

    @Test
    fun `a retry finds its revision on the chain when the head has moved on`() = runBlocking<Unit> {
        val first = changes.propose(PLATO, ACME, 0, listOf(Operation.PutPolicy(allow("reads"))), "reads")
        changes.skipShadow(PLATO, first.id, "test")
        changes.publish(PLATO, first.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        val change = changes.propose(PLATO, ACME, 1, listOf(Operation.PutPolicy(allow("writes"))), "writes")
        lost(change)
        // Another writer, such as a bootstrap, publishes over it before the retry.
        val head = revisions.head(ACME)!!
        revisions.publish(
            ACME,
            head,
            StoreFixtures.candidate(ACME, head.document.policies + allow("later"), emptyList()),
            StoreFixtures.publication("later")
        )
        val again = changes.publish(PLATO, change.id)
        again.state.assert().isEqualTo(ChangeState.PUBLISHED)
        again.publishedRevision.assert().isEqualTo(2L)
        // Its index entry, which the lost reply left out, is written; the later one stays.
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        // A change the chain does not show is conflicted, as before.
        val stale = changes.propose(PLATO, ACME, 3, listOf(Operation.PutPolicy(allow("stale"))), "stale")
        changes.skipShadow(PLATO, stale.id, "test")
        val moved = revisions.head(ACME)!!
        revisions.publish(
            ACME,
            moved,
            StoreFixtures.candidate(ACME, moved.document.policies + allow("later-again"), emptyList()),
            StoreFixtures.publication("later-again")
        )
        changes.publish(PLATO, stale.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        listeners.cancel()
    }

    private companion object {
        val PIA = Operator(Actor(TenantId.PLATFORM, PrincipalId("pia")), NO_MEMBERSHIPS)
        val BAN = listOf(
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
    }
}
