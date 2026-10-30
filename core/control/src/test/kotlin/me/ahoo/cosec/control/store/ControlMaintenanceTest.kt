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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
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
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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

    /**
     * Review of #1181: an app document published by something that does not keep the app list (an instance or a
     * cosec-migrate from before the upgrade, or after a downgrade) makes a complete list incomplete again.
     */
    @Test
    fun `reclamation marks a complete app list incomplete when it misses an app document - RC1-4`() = runBlocking<Unit> {
        val billing = SnapshotKey.App("billing")
        revisions.publish(SnapshotKey.Platform, null, candidate(SnapshotKey.Platform), publication())
        revisions.publish(billing, null, candidate(billing), publication())
        maintenance.reclaim()
        store.apps()!!.value.assert().isEqualTo(AppList(setOf("billing"), complete = true))

        val older = object : ControlStore by store {
            override suspend fun apps(): Versioned<AppList>? = null

            override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean = true
        }
        val legacy = SnapshotKey.App("legacy")
        Revisions(older, clock).publish(legacy, null, candidate(legacy), publication())
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
        store.apps()!!.value.complete.assert().isTrue()

        maintenance.reclaim()
        store.apps()!!.value.assert().isEqualTo(AppList(setOf("billing"), complete = false))
        // Nothing more to do until repair-references lists it.
        revisions.reconcileApps(store.documents()).assert().isEmpty()
        revisions.completeApps(setOf("billing", "legacy")).assert().isTrue()
        maintenance.reclaim()
        store.apps()!!.value.complete.assert().isTrue()
    }

    @Test
    fun `shadow windows are closed by whoever holds the lease, which a pass releases`() = runBlocking<Unit> {
        published()
        val other = ControlMaintenance(store, revisions, changes, Duration.ofMinutes(1))
        val first = staged("first")
        maintenance.closeShadowWindows().map { it.id }.assert().containsExactly(first)
        changes.get(first).state.assert().isEqualTo(ChangeState.READY)
        changes.abandon(ALICE_OP, first)

        // While another instance holds the lease, the window stays open.
        val second = staged("second", base = 1)
        store.acquireLease(ControlMaintenance.SHADOW_LEASE, "someone", Duration.ofMinutes(2)).assert().isTrue()
        other.closeShadowWindows().assert().isEmpty()
        changes.get(second).state.assert().isEqualTo(ChangeState.SHADOWING)
        store.releaseLease(ControlMaintenance.SHADOW_LEASE, "someone")
        // The first pass released its lease: the other instance need not wait for it to expire.
        other.closeShadowWindows().map { it.id }.assert().containsExactly(second)
    }

    @Test
    fun `a pass that cannot read an open change reclaims nothing`() = runBlocking<Unit> {
        val failing = object : ControlStore by store {
            override suspend fun change(id: ChangeId) =
                if (id.value == "c1") error("Redis is down") else store.change(id)
        }
        val blindRevisions = Revisions(failing, clock, revisions.retention)
        val blindChanges = Changes(failing, blindRevisions, { ChangeId("unused") }, PERMIT_ALL, clock)
        val blind = ControlMaintenance(failing, blindRevisions, blindChanges, Duration.ofMinutes(1))
        val first = published().digest
        published()
        changes.propose(ALICE_OP, ACME, 2, listOf(Operation.PutPolicy(allow("read"))), "open")
        published()
        clock.now = clock.now.plus(Duration.ofDays(1))

        blind.reclaim().assert().isEmpty()
        store.recordDigests(ACME).assert().contains(first)
    }

    @Test
    fun `a change nobody finishes expires and leaves the open list`() = runBlocking<Unit> {
        published()
        val stale = changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow("read"))), "stale")
        store.openChanges().assert().containsExactly(stale.id)
        clock.now = clock.now.plus(Duration.ofDays(8))
        maintenance.closeShadowWindows()
        store.change(stale.id)!!.value.state.assert().isEqualTo(ChangeState.EXPIRED)
        store.openChanges().assert().isEmpty()
    }

    private suspend fun staged(policy: String, base: Long = 1): ChangeId {
        val change = changes.propose(ALICE_OP, ACME, base, listOf(Operation.PutPolicy(allow(policy))), policy)
        return changes.stage(ALICE_OP, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }.id
    }
}
