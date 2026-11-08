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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * A publish indexes its revision after its head write, so a later revision may already be indexed when it does. Its
 * entry goes in beside that one: dropping it would leave a revision nothing lists, which `history` cannot find again
 * once a still later revision is indexed, and which reclamation never removes. The interleaving is set by latches on
 * the store, not by timing.
 */
class LateSettlementTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** Holds the index write that follows the head write of a publish once [armed], until [resume]. */
    private val store = object : ControlStore by memory {
        var armed = false
        var written = false
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        /** Runs once, right before the next read of the index. */
        var beforeIndexRead: (suspend () -> Unit)? = null

        override suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>? {
            beforeIndexRead?.let {
                beforeIndexRead = null
                it()
            }
            return memory.index(key)
        }

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite =
            memory.putHead(key, expected, document).also { written = armed && it is HeadWrite.Written }

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
            if (written) {
                written = false
                armed = false
                paused.complete(Unit)
                resume.await()
            }
            return memory.putIndex(key, expected, index)
        }
    }
    private val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 1, minAge = Duration.ZERO))

    /** Another instance, publishing past the store's latches. */
    private val other = Revisions(memory, clock)

    private suspend fun publish(): PublishOutcome.Published {
        clock.now = clock.now.plus(Duration.ofHours(1))
        return revisions.publish(ACME, revisions.head(ACME), candidate(ACME), publication()) as PublishOutcome.Published
    }

    @Test
    fun `a revision indexed late leaves the later revisions indexed`() = runBlocking<Unit> {
        val first = publish()

        store.armed = true
        val late = async { publish() }
        store.paused.await()
        val third = publish()
        store.resume.complete(Unit)
        val second = late.await()
        // Indexed after its late one was, so that history cannot find a missing entry by the head being ahead of it.
        val fourth = publish()

        memory.index(ACME)!!.value.entries.map { it.digest }.assert()
            .containsExactly(first.digest, second.digest, third.digest, fourth.digest)
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L, 4L)
        revisions.revision(ACME, 3).assert().isNotNull()

        // Every revision is listed, so reclamation reaches each: nothing stays behind unlisted.
        clock.now = clock.now.plus(Duration.ofHours(1))
        revisions.reclaim(ACME).records.assert().containsExactly(first.digest, second.digest, third.digest)
        memory.recordDigests(ACME).assert().containsExactly(fourth.digest)
    }

    /**
     * A reclamation that read the head before another instance published reads the index ahead of it: the new revision
     * is left out of that read alone, and what reclamation removes comes from that read, so it is never removed.
     */
    @Test
    fun `a revision published between the head and index reads of a reclamation is not removed`() = runBlocking<Unit> {
        val first = publish()
        val second = publish()
        var third: PublishOutcome.Published? = null
        store.beforeIndexRead = {
            clock.now = clock.now.plus(Duration.ofHours(1))
            third = other.publish(ACME, other.head(ACME), candidate(ACME), publication()) as PublishOutcome.Published
        }
        clock.now = clock.now.plus(Duration.ofHours(1))
        revisions.reclaim(ACME).records.assert().containsExactly(first.digest)

        memory.recordDigests(ACME).assert().containsExactlyInAnyOrder(second.digest, third!!.digest)
        revisions.history(ACME).map { it.digest }.assert().containsExactly(second.digest, third!!.digest)
    }
}
