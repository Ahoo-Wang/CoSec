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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1226: a record reclamation takes out of the index and then fails to delete is deleted by a later pass. The index
 * is written first (a listed revision never misses its record), so what is left to delete is kept in the index with it
 * ([RevisionIndex.reclaiming]) until its deletion succeeds.
 */
class ReclamationRetryTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** Fails the deletions whose 1-based call numbers are in [failing]. */
    private val store = object : ControlStore by memory {
        val failing = HashSet<Int>()
        var deletions = 0

        override suspend fun removeRecord(key: SnapshotKey, digest: SnapshotDigest) {
            deletions++
            check(deletions !in failing) { "deletion $deletions failed" }
            memory.removeRecord(key, digest)
        }
    }
    private val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 1, minAge = Duration.ZERO))
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

    private suspend fun published(): SnapshotDigest {
        clock.now = clock.now.plus(Duration.ofHours(1))
        return (revisions.publish(ACME, revisions.head(ACME), candidate(ACME), publication()) as PublishOutcome.Published)
            .digest
    }

    private suspend fun nextPass() {
        clock.now = clock.now.plus(Duration.ofHours(1))
        maintenance.reclaim()
    }

    @Test
    fun `a record whose deletion failed is deleted by the next pass - #1226`() = runBlocking<Unit> {
        val first = published()
        val second = published()
        store.failing += 1
        nextPass()
        // Taken out of the index, still stored, and listed to delete.
        revisions.history(ACME).map { it.digest }.assert().containsExactly(second)
        memory.recordDigests(ACME).assert().containsExactlyInAnyOrder(first, second)
        memory.index(ACME)!!.value.reclaiming.assert().containsExactly(first)

        clock.now = clock.now.plus(Duration.ofHours(1))
        val retried = revisions.reclaim(ACME)
        retried.records.assert().isEmpty()
        retried.deleted.assert().containsExactly(first)
        memory.recordDigests(ACME).assert().containsExactly(second)
        memory.index(ACME)!!.value.reclaiming.assert().isEmpty()
        store.deletions.assert().isEqualTo(2)
        // Nothing is left to delete, so later passes delete nothing again.
        nextPass()
        nextPass()
        store.deletions.assert().isEqualTo(2)
        revisions.history(ACME).map { it.digest }.assert().containsExactly(second)
    }

    @Test
    fun `a pass whose deletions fail midway deletes the rest, and the next pass the one that failed`() =
        runBlocking<Unit> {
            val digests = (1..4).map { published() }
            store.failing += 2
            clock.now = clock.now.plus(Duration.ofHours(1))
            val pass = revisions.reclaim(ACME)
            pass.records.assert().containsExactly(digests[0], digests[1], digests[2])
            pass.failed.assert().containsExactly(digests[1])
            // The first and the third are deleted; the second is left for the next pass, the head is kept.
            memory.recordDigests(ACME).assert().containsExactlyInAnyOrder(digests[1], digests[3])
            memory.index(ACME)!!.value.reclaiming.assert().containsExactly(digests[1])
            nextPass()
            memory.recordDigests(ACME).assert().containsExactly(digests[3])
            revisions.history(ACME).map { it.digest }.assert().containsExactly(digests[3])
        }

    @Test
    fun `a pass that cannot delete anything keeps every record to delete for the next`() = runBlocking<Unit> {
        val digests = (1..3).map { published() }
        store.failing += listOf(1, 2, 3, 4)
        nextPass()
        nextPass()
        memory.recordDigests(ACME).assert().containsExactlyInAnyOrder(*digests.toTypedArray())
        nextPass()
        memory.recordDigests(ACME).assert().containsExactly(digests[2])
    }

    @Test
    fun `a record left to delete that is the head again is never deleted`() = runBlocking<Unit> {
        published()
        val second = published()
        // As a store restored from a copy would hold it: the head's own record listed to delete.
        val index = memory.index(ACME)!!
        memory.putIndex(ACME, index.digest, index.value.copy(reclaiming = listOf(second))).assert().isTrue()
        nextPass()
        memory.recordDigests(ACME).assert().contains(second)
        memory.index(ACME)!!.value.reclaiming.assert().isEmpty()
        revisions.verifyChain(ACME).assert().isInstanceOf(ChainCheck.Intact::class.java)
    }
}
