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
import me.ahoo.cosec.control.change.ChangeRequest
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
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
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
        store.openChanges().map { it.id }.assert().containsExactly(open.id)
        maintenance.reclaim().getValue(ACME).records.assert().containsExactly(first)
        store.recordDigests(ACME).assert().containsExactlyInAnyOrder(second, third)

        changes.abandon(ALICE_OP, open.id)
        store.openChanges().assert().isEmpty()
        maintenance.reclaim().getValue(ACME).records.assert().containsExactly(second)
        maintenance.reclaim().assert().isEmpty()
    }

    /**
     * SCP-10: a stored open change of an app no key can name does not decode, so nothing knows what it rests on. The
     * pass stops, however the store wraps the error: no revision is reclaimed until an operator removes the record.
     */
    @Test
    fun `an open change of an app no key can name stops the pass`() = runBlocking<Unit> {
        val failing = object : ControlStore by store {
            override suspend fun openChanges(): List<ChangeRequest> =
                throw IllegalStateException("decoding", SnapshotFormatException("A change request is malformed."))
        }
        val failingChanges = Changes(
            failing,
            revisions,
            { ChangeId("s${ids.incrementAndGet()}") },
            PERMIT_ALL,
            clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
        )
        val maintenance = ControlMaintenance(failing, revisions, failingChanges, Duration.ofMinutes(1))
        val first = published().digest
        val second = published().digest
        val third = published().digest
        clock.now = clock.now.plus(Duration.ofDays(1))

        maintenance.reclaim().assert().isEmpty()
        store.recordDigests(ACME).assert().containsExactlyInAnyOrder(first, second, third)
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

        publishUnlisted(SnapshotKey.App("legacy"))
        store.apps()!!.value.complete.assert().isTrue()

        maintenance.reclaim()
        store.apps()!!.value.assert().isEqualTo(AppList(setOf("billing"), complete = false))
        // Nothing more to do until repair-references lists it.
        revisions.reconcileApps(store.documents()).assert().isEmpty()
        revisions.completeApps(setOf("billing", "legacy")).assert().isTrue()
        maintenance.reclaim()
        store.apps()!!.value.complete.assert().isTrue()
    }

    /** A publish by something that does not keep the app list: an older instance or cosec-migrate. */
    private suspend fun publishUnlisted(app: SnapshotKey.App) {
        val older = object : ControlStore by store {
            override suspend fun apps(): Versioned<AppList>? = null

            override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean = true
        }
        Revisions(older, clock).publish(app, null, candidate(app), publication())
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
    }

    /** A pass that cannot list the documents reclaims nothing and reconciles nothing; the next one tries again. */
    @Test
    fun `a pass that cannot list the documents does nothing`() = runBlocking<Unit> {
        published()
        val failing = object : ControlStore by store {
            override suspend fun documents(): Set<SnapshotKey> = error("Redis is down")
        }
        val blindRevisions = Revisions(failing, clock, revisions.retention)
        val blindChanges = Changes(failing, blindRevisions, { ChangeId("unused") }, PERMIT_ALL, clock)
        ControlMaintenance(failing, blindRevisions, blindChanges, Duration.ofMinutes(1)).reclaim().assert().isEmpty()
    }

    /** Review of #1181 (F3): the open changes say what a pass may reclaim, nothing about the app list. */
    @Test
    fun `a pass that cannot read an open change still reconciles the app list - RC1-4`() = runBlocking<Unit> {
        revisions.publish(SnapshotKey.Platform, null, candidate(SnapshotKey.Platform), publication())
        publishUnlisted(SnapshotKey.App("legacy"))
        changes.propose(ALICE_OP, ACME, 0, listOf(Operation.PutPolicy(allow("read"))), "open")
        val failing = object : ControlStore by store {
            override suspend fun change(id: ChangeId) = error("Redis is down")
        }
        val blindRevisions = Revisions(failing, clock, revisions.retention)
        val blindChanges = Changes(failing, blindRevisions, { ChangeId("unused") }, PERMIT_ALL, clock)
        val blind = ControlMaintenance(failing, blindRevisions, blindChanges, Duration.ofMinutes(1))
        blind.reclaim().assert().isEmpty()
        store.apps()!!.value.assert().isEqualTo(AppList(emptySet(), complete = false))
    }

    /**
     * Review of #1181 (F5): a listed app without a head stopped before its head write, so it misses nothing; one whose
     * head cannot be read may have one.
     */
    @Test
    fun `an app the store lists without a head misses nothing, one that cannot be read does - RC1-4`() =
        runBlocking<Unit> {
            revisions.publish(SnapshotKey.Platform, null, candidate(SnapshotKey.Platform), publication())
            val ghost = SnapshotKey.App("ghost")
            store.putIndex(ghost, null, RevisionIndex()).assert().isTrue()
            maintenance.reconcileApps().assert().isEmpty()
            store.apps()!!.value.complete.assert().isTrue()

            val unreadable = object : ControlStore by store {
                override suspend fun head(key: SnapshotKey): StoredDocument? =
                    if (key == ghost) error("Redis is down") else store.head(key)
            }
            Revisions(unreadable, clock).reconcileApps(store.documents()).assert().containsExactly("ghost")
            store.apps()!!.value.complete.assert().isFalse()
        }

    /** Review of #1181: a write whose reply was lost reads back as a conflict, and is still reported. */
    @Test
    fun `a reconciliation whose write reply was lost still reports what it found - RC1-4`() = runBlocking<Unit> {
        revisions.publish(SnapshotKey.Platform, null, candidate(SnapshotKey.Platform), publication())
        publishUnlisted(SnapshotKey.App("legacy"))
        val lossy = object : ControlStore by store {
            override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean {
                store.putApps(expected, apps).assert().isTrue()
                return false
            }
        }
        Revisions(lossy, clock).reconcileApps(store.documents()).assert().containsExactly("legacy")
        store.apps()!!.value.complete.assert().isFalse()
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
            override suspend fun openChanges(): List<ChangeRequest> = error("Redis is down")
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
        store.openChanges().map { it.id }.assert().containsExactly(stale.id)
        clock.now = clock.now.plus(Duration.ofDays(8))
        maintenance.closeShadowWindows()
        store.change(stale.id)!!.value.state.assert().isEqualTo(ChangeState.EXPIRED)
        store.openChanges().assert().isEmpty()
    }

    private suspend fun staged(policy: String, base: Long = 1): ChangeId {
        val change = changes.propose(ALICE_OP, ACME, base, listOf(Operation.PutPolicy(allow(policy))), policy)
        return changes.stage(ALICE_OP, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }.id
    }

    /** A store that counts how often each change is read, and how often the open changes are listed. */
    private inner class Counting : ControlStore by store {
        val reads = java.util.concurrent.ConcurrentHashMap<ChangeId, Int>()
        var listings = 0

        override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? {
            reads.merge(id, 1, Int::plus)
            return store.change(id)
        }

        override suspend fun openChanges(): List<ChangeRequest> {
            listings++
            return store.openChanges()
        }
    }

    private fun counting(): Pair<Counting, ControlMaintenance> {
        val counting = Counting()
        val countingRevisions = Revisions(counting, clock, revisions.retention)
        val countingChanges = Changes(
            counting,
            countingRevisions,
            { ChangeId("n${ids.incrementAndGet()}") },
            PERMIT_ALL,
            clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
        )
        return counting to ControlMaintenance(counting, countingRevisions, countingChanges, Duration.ofMinutes(1))
    }

    /** #1219: the open changes are listed once, and the listing is what the pass goes by. */
    @Test
    fun `a pass reads the open changes once and none again - #1219`() = runBlocking<Unit> {
        published()
        val open = (1..20).map {
            changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow("p$it"))), "open $it")
        }
        val shadowing = staged("shadowed", 1)
        val (counting, maintenance) = counting()

        maintenance.reclaim()

        counting.listings.assert().isEqualTo(1)
        counting.reads.assert().isEmpty()

        counting.listings = 0
        maintenance.closeShadowWindows()

        counting.listings.assert().isEqualTo(1)
        // Only the one change that is shadowing is looked at again, to close its window; the others are not read.
        counting.reads.keys.assert().containsExactly(shadowing)
        open.forEach { changes.get(it.id).state.assert().isEqualTo(ChangeState.CHECKED) }
    }

    /** #1219: what each document keeps comes from its own open changes, found without a scan of all of them for each. */
    @Test
    fun `each document keeps what its own open changes rest on - #1219`() = runBlocking<Unit> {
        val beta = SnapshotKey.Tenant("beta")
        suspend fun publish(key: SnapshotKey): SnapshotDigest {
            clock.now = clock.now.plus(Duration.ofDays(1))
            return (
                revisions.publish(
                    key,
                    revisions.head(key),
                    candidate(key),
                    publication()
                ) as PublishOutcome.Published
                )
                .digest
        }
        val acme = mutableListOf(publish(ACME))
        val other = mutableListOf(publish(beta))
        // acme's open change rests on its first revision, which retention (the latest only) would otherwise remove.
        changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow("read"))), "open")
        repeat(2) {
            acme += publish(ACME)
            other += publish(beta)
        }
        clock.now = clock.now.plus(Duration.ofDays(1))

        val reclaimed = maintenance.reclaim()

        reclaimed.getValue(ACME).records.assert().containsExactly(acme[1])
        reclaimed.getValue(beta).records.assert().containsExactlyInAnyOrder(other[0], other[1])
        store.recordDigests(ACME).assert().containsExactlyInAnyOrder(acme[0], acme[2])
        store.recordDigests(beta).assert().containsExactly(other[2])
    }
}
