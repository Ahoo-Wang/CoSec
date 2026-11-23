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
import me.ahoo.cosec.control.change.ALICE_OP
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** `CTL-83` (DR1-E-2): a document keeps at most max-count ended changes, and reclamation removes the old ones. */
class EndedChangesTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val ids = AtomicInteger()

    private fun changes(revisions: Revisions) = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
    )

    private suspend fun ended(changes: Changes): ChangeRequest {
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        val change = changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow("read"))), "ends")
        return changes.abandon(ALICE_OP, change.id)
    }

    private suspend fun listed(): List<ChangeId> = store.endedChanges(ACME, Instant.MAX, Int.MAX_VALUE).map { it.id }

    @Test
    fun `a document keeps at most max-count ended changes, the oldest going first whatever its age`() = runBlocking<Unit> {
        val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 2))
        revisions.publish(ACME, null, candidate(ACME), publication())
        val changes = changes(revisions)
        val first = ended(changes)
        val second = ended(changes)
        listed().assert().containsExactly(first.id, second.id)

        val third = ended(changes)

        listed().assert().containsExactly(second.id, third.id)
        store.change(first.id).assert().isNull()
        store.change(second.id).assert().isNotNull()
    }

    @Test
    fun `the bound never fails the write that ended a change`() = runBlocking<Unit> {
        val failing = object : ControlStore by store {
            override suspend fun endedChanges(
                document: SnapshotKey,
                endedBefore: Instant,
                limit: Int
            ): List<EndedChange> =
                error("Redis is down")
        }
        val revisions = Revisions(failing, clock, RetentionPolicy(maxCount = 1))
        revisions.publish(ACME, null, candidate(ACME), publication())
        val changes = Changes(
            failing,
            revisions,
            { ChangeId("f${ids.incrementAndGet()}") },
            PERMIT_ALL,
            clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
            revocations = RevocationReader.NONE,
            lockout = LockoutCheck.UNCHECKED,
        )

        ended(changes).state.assert().isEqualTo(ChangeState.ABANDONED)
        ended(changes).state.assert().isEqualTo(ChangeState.ABANDONED)
        listed().size.assert().isEqualTo(2)
    }

    @Test
    fun `reclamation removes ended changes past min-age that published no revision still retained`() = runBlocking<Unit> {
        val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 5, minAge = Duration.ofHours(1)))
        revisions.publish(ACME, null, candidate(ACME), publication())
        val changes = changes(revisions)
        val abandoned = ended(changes)
        // A published change, as the lifecycle ends one: of revision 1, which stays retained.
        val published = abandoned.copy(id = ChangeId("published"), state = ChangeState.PUBLISHED, publishedRevision = 1)
        store.putChange(null, published).assert().isTrue()
        val maintenance = ControlMaintenance(store, revisions, changes, Duration.ofMinutes(1))
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        maintenance.reclaim()
        // Half of min-age: nothing goes.
        listed().assert().containsExactlyInAnyOrder(abandoned.id, published.id)

        clock.now = clock.now.plus(Duration.ofMinutes(30))
        val young = ended(changes)
        maintenance.reclaim()

        listed().assert().containsExactlyInAnyOrder(published.id, young.id)
        store.change(abandoned.id).assert().isNull()
        store.change(published.id).assert().isNotNull()
    }

    @Test
    fun `a listed change whose record is gone leaves the list, and a record another wrote stays`() = runBlocking<Unit> {
        val revisions = Revisions(store, clock)
        revisions.publish(ACME, null, candidate(ACME), publication())
        val change = ended(changes(revisions))
        val digest = store.change(change.id)!!.digest

        store.removeChange(ACME, change.id, null).assert().isFalse()
        store.removeChange(ACME, change.id, store.head(ACME)!!.digest).assert().isFalse()
        store.change(change.id).assert().isNotNull()
        store.removeChange(ACME, change.id, digest).assert().isTrue()
        store.removeChange(ACME, change.id, null).assert().isTrue()
        listed().assert().isEmpty()
    }
}
