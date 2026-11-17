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
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * #1532: `verify-chain` runs beside the control plane, whose reclamation moves the index floor and deletes the records
 * below it. A record found missing is a lost write only above the floor as it is when it is found missing, not as it
 * was when the walk began. The interleaving runs at the walk's first read of a record, through a store that lets
 * another instance reclaim there.
 */
class VerifyChainDuringReclaimTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val retention = RetentionPolicy(maxCount = 2, minAge = Duration.ofDays(1))

    /** Runs [beforeFirstRecord] once, right before the first record is read. */
    private val store = object : ControlStore by memory {
        var beforeFirstRecord: (suspend () -> Unit)? = null

        override suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument? {
            beforeFirstRecord?.let { race ->
                beforeFirstRecord = null
                race()
            }
            return memory.record(key, digest)
        }
    }
    private val revisions = Revisions(store, clock, retention)

    /** Four revisions, two days apart: with two kept and one day's minimum age, 1 and 2 are due for reclamation. */
    private suspend fun publishedFour(): List<SnapshotDigest> = (1..4).map {
        clock.now = clock.now.plus(Duration.ofDays(2))
        val document: PolicyDocument = candidate(ACME)
        (revisions.publish(ACME, revisions.head(ACME), document, publication()) as PublishOutcome.Published).digest
    }

    @Test
    fun `normal reclamation during the walk is not a lost revision`() = runBlocking<Unit> {
        publishedFour()
        revisions.verifyChain(ACME).assert().isInstanceOf(ChainCheck.Intact::class.java)
        store.beforeFirstRecord = { revisions.reclaim(ACME).records.size.assert().isEqualTo(2) }

        val check = revisions.verifyChain(ACME)

        check.assert().isInstanceOf(ChainCheck.Intact::class.java)
        (check as ChainCheck.Intact).oldest.assert().isEqualTo(3)
        revisions.verifyChain(ACME).assert().isInstanceOf(ChainCheck.Intact::class.java)
    }

    @Test
    fun `what was reclaimed before the walk began is not a lost revision either`() = runBlocking<Unit> {
        publishedFour()
        revisions.reclaim(ACME).records.size.assert().isEqualTo(2)

        (revisions.verifyChain(ACME) as ChainCheck.Intact).oldest.assert().isEqualTo(3)
    }

    /** The negative side: what the floor does not cover is still lost, whenever the walk finds it. */
    @Test
    fun `a record missing above the floor as it is now is still broken`() = runBlocking<Unit> {
        val digests = publishedFour()
        store.beforeFirstRecord = {
            revisions.reclaim(ACME)
            // Revision 3 is above the floor reclamation has just moved to (3): nothing reclaimed it.
            memory.removeRecord(ACME, digests[2])
        }

        val check = revisions.verifyChain(ACME)

        (check as ChainCheck.Broken).missing.revision.assert().isEqualTo(3)
    }

    @Test
    fun `a record missing above the floor with no reclamation at all is broken`() = runBlocking<Unit> {
        val digests = publishedFour()
        memory.removeRecord(ACME, digests[1])

        ((revisions.verifyChain(ACME)) as ChainCheck.Broken).missing.revision.assert().isEqualTo(2)
    }

    @Test
    fun `without an index nothing missing is taken for reclaimed`() = runBlocking<Unit> {
        val digests = publishedFour()
        memory.dropIndex(ACME)
        memory.removeRecord(ACME, digests[1])

        (revisions.verifyChain(ACME) as ChainCheck.Broken).missing.revision.assert().isEqualTo(2)
    }
}
