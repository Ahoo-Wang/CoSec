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
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.binding
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/** RFC 0007 §5.4–§5.8 over [InMemoryControlStore]; `RevisionChainTest` in `cosec-cocache` runs the chain on Redis. */
class RevisionsTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val calls = ArrayList<String>()

    /** Records the order of writes and fails the ones a test asks it to. */
    private val store = object : ControlStore by memory {
        var failHead = false
        var failRecord = false
        var failIndex = false

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite {
            calls += "head"
            check(!failHead) { "head write failed" }
            return memory.putHead(key, expected, document)
        }

        override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest {
            calls += "record"
            check(!failRecord) { "record write failed" }
            return memory.putRecord(key, document)
        }

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
            calls += "index"
            return !failIndex && memory.putIndex(key, expected, index)
        }
    }
    private val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 2, minAge = Duration.ofDays(1)))

    private fun publish(
        key: SnapshotKey = ACME,
        document: me.ahoo.cosec.snapshot.document.PolicyDocument = candidate(
            key
        ),
        change: String = "c"
    ) =
        runBlocking { revisions.publish(key, revisions.head(key), document, publication(change)) }

    private fun published(
        key: SnapshotKey = ACME,
        document: me.ahoo.cosec.snapshot.document.PolicyDocument = candidate(
            key
        )
    ) =
        publish(key, document) as PublishOutcome.Published

    @Nested
    inner class WriteOrder {
        @Test
        fun `the first revision has no parent and its record is the head's digest`() = runBlocking<Unit> {
            val first = published()
            first.revision.assert().isEqualTo(1)
            val head = revisions.head(ACME)!!
            head.digest.assert().isEqualTo(first.digest)
            head.document.provenance!!.parent.assert().isNull()
            head.document.provenance!!.publishedBy.assert().isEqualTo(StoreFixtures.ALICE)
            memory.record(ACME, first.digest).assert().isEqualTo(head.stored.document)
            revisions.history(ACME).map { it.revision }.assert().containsExactly(1L)
            memory.index(ACME)!!.value.pending.assert().isEmpty()
        }

        @Test
        fun `every revision names its parent, and its record is written before the head`() = runBlocking<Unit> {
            val first = published()
            calls.clear()
            val second = published(document = candidate(ACME, listOf(policy("p"))))
            second.revision.assert().isEqualTo(2)
            val parent = revisions.head(ACME)!!.document.provenance!!.parent!!
            parent.revision.assert().isEqualTo(1)
            parent.digest.assert().isEqualTo(first.digest.toString())
            calls.indexOf("record").assert().isLessThan(calls.indexOf("head"))
            memory.record(ACME, second.digest).assert().isNotNull()
            revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L)
        }

        @Test
        fun `a stale base conflicts and overwrites nothing`() = runBlocking<Unit> {
            published()
            val stale = revisions.head(ACME)
            val winner = published(document = candidate(ACME, listOf(policy("winner"))))
            val outcome = revisions.publish(ACME, stale, candidate(ACME, listOf(policy("loser"))), publication("late"))
            (outcome as PublishOutcome.Conflicted).current.assert().isEqualTo(winner.digest)
            revisions.head(ACME)!!.document.policies.map { it.id.value }.assert().containsExactly("winner")
            // The loser's record stays pending, an orphan for reclamation.
            memory.index(ACME)!!.value.pending.size.assert().isEqualTo(1)
        }

        @Test
        fun `a failed head write keeps the record and the old head`() = runBlocking<Unit> {
            published()
            store.failHead = true
            assertThrownBy<IllegalStateException> { publish(document = candidate(ACME, listOf(policy("p")))) }
            store.failHead = false
            revisions.head(ACME)!!.revision.assert().isEqualTo(1)
            memory.recordDigests(ACME).size.assert().isEqualTo(2)
        }

        @Test
        fun `a failed record write never moves the head`() = runBlocking<Unit> {
            published()
            calls.clear()
            store.failRecord = true
            assertThrownBy<IllegalStateException> { publish(document = candidate(ACME, listOf(policy("p")))) }
            calls.assert().doesNotContain("head")
            revisions.head(ACME)!!.revision.assert().isEqualTo(1)
        }

        @Test
        fun `only platform, app and tenant documents are published`() {
            assertThrownBy<IllegalArgumentException> {
                runBlocking { revisions.publish(SnapshotKey.Tenant("beta"), null, candidate(ACME), publication()) }
            }
        }
    }

    @Nested
    inner class Chain {
        @Test
        fun `an index that fell behind or was lost is rebuilt from the chain`() = runBlocking<Unit> {
            published()
            store.failIndex = true
            published()
            published()
            store.failIndex = false
            memory.index(ACME)!!.value.entries.map { it.revision }.assert().containsExactly(1L)
            revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
            memory.index(ACME)!!.value.entries.map { it.revision }.assert().containsExactly(1L, 2L, 3L)
            memory.index(ACME)!!.value.pending.assert().isEmpty()
            memory.dropIndex(ACME)
            revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        }

        @Test
        fun `a missing record is a broken chain, a reclaimed one is not`() = runBlocking<Unit> {
            val first = published()
            published()
            published()
            revisions.verifyChain(ACME).assert().isInstanceOf(ChainCheck.Intact::class.java)
            (revisions.verifyChain(ACME) as ChainCheck.Intact).oldest.assert().isEqualTo(1)
            memory.removeRecord(ACME, revisions.history(ACME)[1].digest)
            (revisions.verifyChain(ACME) as ChainCheck.Broken).missing.revision.assert().isEqualTo(2)
            memory.removeRecord(ACME, revisions.head(ACME)!!.digest)
            (revisions.verifyChain(ACME) as ChainCheck.Broken).missing.revision.assert().isEqualTo(3)
            memory.removeRecord(ACME, first.digest)
            revisions.verifyChain(SnapshotKey.Tenant("nobody")).assert().isInstanceOf(ChainCheck.Intact::class.java)
        }

        @Test
        fun `a rollback restores a whole revision as a new one`() = runBlocking<Unit> {
            published(document = candidate(ACME, listOf(policy("old")), listOf(binding("b", "old"))))
            published(document = candidate(ACME, listOf(policy("new"))))
            val body = revisions.rollbackCandidate(ACME, 1)
            body.policies.map { it.id.value }.assert().containsExactly("old")
            val rolledBack = revisions.publish(
                ACME,
                revisions.head(ACME),
                body,
                publication("rb", ProvenanceKind.ROLLBACK)
            )
                as PublishOutcome.Published
            rolledBack.revision.assert().isEqualTo(3)
            val head = revisions.head(ACME)!!.document
            head.provenance!!.kind.assert().isEqualTo(ProvenanceKind.ROLLBACK)
            head.provenance!!.parent!!.revision.assert().isEqualTo(2)
            head.bindings.map { it.id.value }.assert().containsExactly("b")
            assertThrownBy<IllegalArgumentException> { runBlocking { revisions.rollbackCandidate(ACME, 9) } }
        }
    }

    @Nested
    inner class Reclamation {
        @Test
        fun `records beyond the retained count and age are removed, the kept ones are not`() = runBlocking<Unit> {
            val digests = (1..5).map {
                clock.now = clock.now.plus(Duration.ofDays(2))
                published().digest
            }
            val reclaimed = revisions.reclaim(ACME, keep = setOf(digests[0]))
            reclaimed.records.assert().containsExactlyInAnyOrder(digests[1], digests[2])
            memory.recordDigests(ACME).assert().containsExactlyInAnyOrder(digests[0], digests[3], digests[4])
            memory.index(ACME)!!.value.floor.assert().isEqualTo(4)
            revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 4L, 5L)
            revisions.verifyChain(ACME).assert().isInstanceOf(ChainCheck.Intact::class.java)
        }

        @Test
        fun `young revisions are kept whatever the count`() = runBlocking<Unit> {
            repeat(4) { published() }
            revisions.reclaim(ACME).records.assert().isEmpty()
        }

        @Test
        fun `orphans go once old, in-flight records stay`() = runBlocking<Unit> {
            published()
            val stale = revisions.head(ACME)
            published()
            revisions.publish(ACME, stale, candidate(ACME, listOf(policy("lost"))), publication("late"))
            revisions.reclaim(ACME).orphans.assert().isEmpty()
            clock.now = clock.now.plus(Duration.ofHours(2))
            val orphan = revisions.reclaim(ACME).orphans.single()
            memory.recordDigests(ACME).assert().doesNotContain(orphan)
            memory.index(ACME)!!.value.pending.assert().isEmpty()
        }

        @Test
        fun `another instance's lease reclaims nothing`() = runBlocking<Unit> {
            repeat(4) {
                clock.now = clock.now.plus(Duration.ofDays(2))
                published()
            }
            memory.acquireLease("reclaim:$ACME", "someone else", Duration.ofMinutes(5)).assert().isTrue()
            revisions.reclaim(ACME).records.assert().isEmpty()
            clock.now = clock.now.plus(Duration.ofMinutes(6))
            revisions.reclaim(ACME).records.size.assert().isEqualTo(2)
        }
    }

    @Nested
    inner class BackReferences {
        private val shared = PolicyId("shared")

        @Test
        fun `a tenant's references to platform and app policies are indexed, its own are not`() = runBlocking<Unit> {
            published(
                document = candidate(
                    ACME,
                    listOf(policy("own")),
                    listOf(binding("a", "shared"), binding("b", "perm", app = "order"), binding("c", "own"))
                )
            )
            revisions.referrers(SnapshotKey.Platform, shared).assert().containsExactly(ACME)
            revisions.referrers(SnapshotKey.Platform, PolicyId("perm")).assert().containsExactly(ACME)
            revisions.referrers(SnapshotKey.App("order"), PolicyId("perm")).assert().containsExactly(ACME)
            revisions.referrers(SnapshotKey.Platform, PolicyId("own")).assert().isEmpty()
        }

        @Test
        fun `removing a referenced policy is refused until nothing refers to it`() = runBlocking<Unit> {
            published(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("shared"), policy("other"))))
            published(document = candidate(ACME, bindings = listOf(binding("a", "shared"))))
            val refused = publish(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("other"))))
            (refused as PublishOutcome.Referenced).referrers.assert().isEqualTo(mapOf(shared to setOf(ACME)))
            revisions.head(SnapshotKey.Platform)!!.revision.assert().isEqualTo(1)
            // Changing what it says is not refused: it applies to every referrer.
            published(
                SnapshotKey.Platform,
                candidate(
                    SnapshotKey.Platform,
                    listOf(policy("shared", me.ahoo.cosec.model.Effect.DENY), policy("other"))
                )
            )
            published(document = candidate(ACME))
            revisions.referrers(SnapshotKey.Platform, shared).assert().isEmpty()
            published(
                SnapshotKey.Platform,
                candidate(SnapshotKey.Platform, listOf(policy("other")))
            ).revision.assert().isEqualTo(3)
        }

        @Test
        fun `repairing adds what a head refers to`() = runBlocking<Unit> {
            published(document = candidate(ACME, bindings = listOf(binding("a", "shared"))))
            memory.putReferrers(
                SnapshotKey.Platform,
                shared,
                memory.referrers(SnapshotKey.Platform, shared)!!.digest,
                emptySet()
            )
            revisions.referrers(SnapshotKey.Platform, shared).assert().isEmpty()
            revisions.repairReferences(ACME)
            revisions.referrers(SnapshotKey.Platform, shared).assert().containsExactly(ACME)
        }
    }
}
