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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.change

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.lifecycle.ShadowOptions
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `CTL-46`: the staged list is a superset of what is staged. A stage lists its window before it writes its candidate, and
 * a listing that fails fails the stage with nothing written; a candidate is withdrawn before its window is unlisted; the
 * shadow pass lists what a lost write left unlisted and unlists, after a pass of grace, a window nothing stages.
 */
class StagedListTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** The in-memory store, recording the order of the writes this rule is about, and failing a listing on demand. */
    private class Ordered(private val memory: InMemoryControlStore) : ControlStore by memory {
        val writes = CopyOnWriteArrayList<String>()

        @Volatile
        var failListing = false

        @Volatile
        var failWithdrawal = false

        @Volatile
        var failUnlisting = false

        override suspend fun listStaged(key: SnapshotKey, window: String) {
            if (failListing) error("Redis refused the listing")
            writes += "list"
            memory.listStaged(key, window)
        }

        override suspend fun unlistStaged(key: SnapshotKey, window: String) {
            if (failUnlisting) error("Redis refused the unlisting")
            writes += "unlist"
            memory.unlistStaged(key, window)
        }

        override suspend fun putCandidate(key: SnapshotKey, expected: SnapshotDigest?, candidate: StoredCandidate): Boolean {
            writes += "candidate"
            return memory.putCandidate(key, expected, candidate)
        }

        override suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean {
            if (failWithdrawal) error("Redis refused the withdrawal")
            writes += "withdraw"
            return memory.withdrawCandidate(key, window)
        }
    }

    private val store = Ordered(memory)

    /** Windows that stay open: the shadow pass looks at them and leaves them shadowing. */
    private val harness = ChangeHarness(clock, memory, store, ChangeOptions(shadow = OPEN))
    private val changes = harness.changes
    private val maintenance = ControlMaintenance(store, harness.revisions, changes, Duration.ofMinutes(1))

    private suspend fun proposed(key: SnapshotKey, name: String): ChangeRequest {
        harness.revisions.publish(key, null, StoreFixtures.candidate(key), StoreFixtures.publication())
        return changes.propose(PLATO, key, 1, listOf(Operation.PutPolicy(allow(name))), name)
    }

    private suspend fun listed(): Map<SnapshotKey, Set<String>>? = memory.staged()?.windows

    @Test
    fun `a stage lists its window before its candidate, and a withdrawal unlists it after`() = runBlocking<Unit> {
        memory.keepStagedList()
        val staged = changes.startShadow(PLATO, proposed(ACME, "first").id)
        val window = staged.shadow!!.window
        listed().assert().isEqualTo(mapOf(ACME to setOf(window)))
        changes.abandon(PLATO, staged.id)
        listed().assert().isEqualTo(emptyMap<SnapshotKey, Set<String>>())
        store.writes.assert().containsExactly("list", "candidate", "withdraw", "unlist")
    }

    @Test
    fun `a listing that fails fails the stage, with nothing staged`() = runBlocking<Unit> {
        memory.keepStagedList()
        val change = proposed(ACME, "first")
        store.failListing = true
        val refused = runCatching { changes.startShadow(PLATO, change.id) }.exceptionOrNull() as ChangeRejectedException
        refused.status.assert().isEqualTo(ControlStatus.UNAVAILABLE)
        memory.candidate(ACME).assert().isNull()
        memory.stage(ACME)?.value?.holder.assert().isNull()
        changes.get(change.id).state.assert().isEqualTo(ChangeState.CHECKED)
        store.writes.assert().doesNotContain("candidate")
    }

    @Test
    fun `the shadow pass keeps the list, lists a staged candidate a lost write left out, and unlists a stale window after a pass of grace`() =
        runBlocking<Unit> {
            listed().assert().isNull()
            val staged = changes.startShadow(PLATO, proposed(ACME, "first").id)
            val window = staged.shadow!!.window
            // The member was lost (a crash between the steps, or a key lost and found again), the candidate stayed.
            memory.unlistStaged(ACME, window)
            // A window whose stage never got to its candidate.
            memory.listStaged(SnapshotKey.Platform, "ghost@1")
            maintenance.closeShadowWindows()
            listed().assert().isEqualTo(mapOf(ACME to setOf(window), SnapshotKey.Platform to setOf("ghost@1")))
            maintenance.closeShadowWindows()
            listed().assert().isEqualTo(mapOf(ACME to setOf(window)))
            changes.get(staged.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        }

    @Test
    fun `a withdrawal that fails keeps its window listed, and the shadow pass withdraws the candidate, then unlists it`() =
        runBlocking<Unit> {
            memory.keepStagedList()
            val staged = changes.startShadow(PLATO, proposed(ACME, "first").id)
            val window = staged.shadow!!.window
            store.failWithdrawal = true
            changes.abandon(PLATO, staged.id)
            // R2C-15: the change is finished, its candidate still staged, and its window still listed.
            memory.candidate(ACME)?.window.assert().isEqualTo(window)
            listed().assert().isEqualTo(mapOf(ACME to setOf(window)))
            store.failWithdrawal = false
            maintenance.closeShadowWindows()
            memory.candidate(ACME)?.window.assert().isEqualTo(window)
            maintenance.closeShadowWindows()
            memory.candidate(ACME).assert().isNull()
            listed().assert().isEqualTo(emptyMap<SnapshotKey, Set<String>>())
        }

    @Test
    fun `a shadowing change whose candidate is gone is checked again after the grace, its slot freed and its window unlisted`() =
        runBlocking<Unit> {
            memory.keepStagedList()
            val staged = changes.startShadow(PLATO, proposed(ACME, "first").id)
            val window = staged.shadow!!.window
            // A crash between the reservation and the write, or a candidate lost since: listed, shadowing, nothing staged.
            memory.withdrawCandidate(ACME, window)
            maintenance.closeShadowWindows()
            changes.get(staged.id).state.assert().isEqualTo(ChangeState.SHADOWING)
            clock.now = clock.now.plus(Duration.ofMinutes(2))
            maintenance.closeShadowWindows()
            val settled = changes.get(staged.id)
            settled.state.assert().isEqualTo(ChangeState.CHECKED)
            settled.shadow.assert().isNull()
            memory.stage(ACME)?.value?.holder.assert().isNull()
            listed().assert().isEqualTo(emptyMap<SnapshotKey, Set<String>>())
            // Its author stages it again.
            changes.startShadow(PLATO, staged.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        }

    @Test
    fun `an unlisting that fails leaves the window listed with its candidate gone, and the shadow pass unlists it`() =
        runBlocking<Unit> {
            memory.keepStagedList()
            val staged = changes.startShadow(PLATO, proposed(ACME, "first").id)
            store.failUnlisting = true
            changes.abandon(PLATO, staged.id)
            memory.candidate(ACME).assert().isNull()
            listed().assert().isEqualTo(mapOf(ACME to setOf(staged.shadow!!.window)))
            store.failUnlisting = false
            maintenance.closeShadowWindows()
            maintenance.closeShadowWindows()
            listed().assert().isEqualTo(emptyMap<SnapshotKey, Set<String>>())
        }

    /** A store that keeps no staged list: the port's defaults. */
    private class Unlisted(memory: InMemoryControlStore) : ControlStore by memory {
        override suspend fun staged() = super<ControlStore>.staged()
        override suspend fun listStaged(key: SnapshotKey, window: String) = super<ControlStore>.listStaged(key, window)
        override suspend fun unlistStaged(key: SnapshotKey, window: String) =
            super<ControlStore>.unlistStaged(key, window)

        override suspend fun keepStagedList() = super<ControlStore>.keepStagedList()
    }

    @Test
    fun `a store that keeps no list stages and withdraws as before, and lists nothing`() = runBlocking<Unit> {
        val unlisted = Unlisted(memory)
        val plain = ChangeHarness(clock, memory, unlisted, ChangeOptions(shadow = OPEN))
        plain.revisions.publish(ACME, null, StoreFixtures.candidate(ACME), StoreFixtures.publication())
        val change = plain.changes.propose(PLATO, ACME, 1, listOf(Operation.PutPolicy(allow("first"))), "first")
        val staged = plain.changes.startShadow(PLATO, change.id)
        memory.candidate(ACME)?.window.assert().isEqualTo(staged.shadow!!.window)
        ControlMaintenance(unlisted, plain.revisions, plain.changes, Duration.ofMinutes(1)).closeShadowWindows()
        unlisted.staged().assert().isNull()
        plain.changes.abandon(PLATO, staged.id)
        memory.candidate(ACME).assert().isNull()
        memory.staged().assert().isNull()
    }

    @Test
    fun `fifty tenants staging at once are all staged and all listed`() = runBlocking<Unit> {
        memory.keepStagedList()
        val tenants = (1..STAGES).map { SnapshotKey.Tenant("tenant-$it") }
        val proposals = tenants.map { proposed(it, "p") }
        val start = CompletableDeferred<Unit>()
        val staged = proposals.map { change ->
            async(Dispatchers.Default) {
                start.await()
                changes.startShadow(PLATO, change.id)
            }
        }.also { start.complete(Unit) }.awaitAll()
        staged.forEach { it.state.assert().isEqualTo(ChangeState.SHADOWING) }
        listed().assert().isEqualTo(staged.associate { it.key to setOf(it.shadow!!.window) })
    }

    private companion object {
        val OPEN = ShadowOptions(Duration.ofHours(1), 1_000, Duration.ofHours(24))
        const val STAGES = 50
    }
}
