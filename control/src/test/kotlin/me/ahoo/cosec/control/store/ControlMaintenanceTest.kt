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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.change.ALICE_OP
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** RFC 0007 §5.7 and §6.4: the periodic passes the host runs over what the store lists. */
class ControlMaintenanceTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 1, minAge = Duration.ofHours(1)))
    private val ids = AtomicInteger()
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
    )
    private val maintenance = ControlMaintenance(store, revisions, changes, Duration.ofMinutes(1))

    private suspend fun published(): PublishOutcome.Published {
        clock.now = clock.now.plus(Duration.ofDays(1))
        return revisions.publish(ACME, revisions.head(ACME), candidate(ACME), publication()) as PublishOutcome.Published
    }

    @Test
    fun `reclamation visits every document and keeps what open changes rest on`() = runBlocking<Unit> {
        val first = published().digest
        val second = published().digest
        val open = changes.propose(ALICE_OP, ACME, 2, listOf(Operation.PutPolicy(allow("read"))), "open")
        val third = published().digest
        clock.now = clock.now.plus(Duration.ofDays(1))

        store.documents().assert().containsExactly(ACME)
        store.openChanges().assert().containsExactly(open.id)
        maintenance.reclaim().getValue(ACME).records.assert().containsExactly(first)
        store.recordDigests(ACME).assert().containsExactlyInAnyOrder(second, third)

        changes.abandon(ALICE_OP, open.id)
        store.openChanges().assert().isEmpty()
        maintenance.reclaim().getValue(ACME).records.assert().containsExactly(second)
        maintenance.reclaim().assert().isEmpty()
    }

    @Test
    fun `shadow windows are closed by one instance per period`() = runBlocking<Unit> {
        published()
        val other = ControlMaintenance(store, revisions, changes, Duration.ofMinutes(1))
        val first = staged("first")
        maintenance.closeShadowWindows().map { it.id }.assert().containsExactly(first)
        changes.get(first).state.assert().isEqualTo(ChangeState.READY)
        changes.abandon(ALICE_OP, first)

        // The other instance waits for the lease; the window stays open until it is its turn.
        val second = staged("second", base = 1)
        other.closeShadowWindows().assert().isEmpty()
        changes.get(second).state.assert().isEqualTo(ChangeState.SHADOWING)
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        other.closeShadowWindows().map { it.id }.assert().containsExactly(second)
    }

    private suspend fun staged(policy: String, base: Long = 1): ChangeId {
        val change = changes.propose(ALICE_OP, ACME, base, listOf(Operation.PutPolicy(allow(policy))), policy)
        return changes.stage(ALICE_OP, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }.id
    }
}
