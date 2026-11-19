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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.captured
import me.ahoo.cosec.control.forget
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1536: the head is the authority. After the head moved back and a revision number was reused, the history is the
 * head's chain (digest by digest), never the old branch's entry of the same number; the old branch's records are
 * left to reclamation, and nothing below the retention floor comes back (#1265). A record read is counted, so what the
 * ordinary path costs is pinned too.
 */
class HistoryForkTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val retention = RetentionPolicy(maxCount = 2, minAge = Duration.ofDays(1))

    /** Fails one index write on request, counts record reads, and runs one hook right after a record read. */
    private val store = object : ControlStore by memory {
        /** Fails the next index write that holds no pending record: the settle of a publication. */
        var failNextSettle = false
        val recordReads = AtomicInteger()
        var afterRecordRead: (suspend () -> Unit)? = null
        var beforeIndexWrite: (suspend () -> Unit)? = null

        /** Holds the index write that follows the head write of a publish once [armed], until [resume]. */
        var armed = false
        private var written = false
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite =
            memory.putHead(key, expected, document).also { written = armed && it is HeadWrite.Written }

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
            if (written) {
                written = false
                armed = false
                paused.complete(Unit)
                resume.await()
            }
            beforeIndexWrite?.let { hook ->
                beforeIndexWrite = null
                hook()
            }
            if (failNextSettle && index.pending.isEmpty()) {
                failNextSettle = false
                error("the index write failed")
            }
            return memory.putIndex(key, expected, index)
        }

        override suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument? {
            recordReads.incrementAndGet()
            val read = memory.record(key, digest)
            afterRecordRead?.let { hook ->
                afterRecordRead = null
                hook()
            }
            return read
        }
    }
    private val revisions = Revisions(store, clock, retention)
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun capture() {
        root.addAppender(appender)
    }

    @AfterEach
    fun release() {
        root.detachAppender(appender)
    }

    private suspend fun publish(policyId: String): SnapshotDigest {
        clock.now = clock.now.plus(Duration.ofHours(1))
        val outcome = revisions.publish(
            ACME,
            revisions.head(ACME),
            candidate(ACME, listOf(policy(policyId))),
            publication(),
        )
        return (outcome as PublishOutcome.Published).digest
    }

    private suspend fun policiesAt(revision: Long): List<String> =
        revisions.revision(ACME, revision)!!.policies.map { it.id.value }

    /** Revisions 1 and 2 (`old-branch`), then the head restored to revision 1 with the index and records kept. */
    private suspend fun headMovedBack(): SnapshotDigest {
        val first = publish("base")
        val old = publish("old-branch")
        val restored = memory.record(ACME, first)!!
        memory.putHead(ACME, old, restored).assert().isInstanceOf(HeadWrite.Written::class.java)
        return old
    }

    private fun warnings() = appender.captured().filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun `history repairs the reused revision after the index write failed`() = runBlocking<Unit> {
        val old = headMovedBack()
        store.failNextSettle = true
        val new = publish("new-branch")

        revisions.history(ACME).last().digest.assert().isEqualTo(new)
        policiesAt(2).assert().containsExactly("new-branch")
        // The repair is written back: a second reading is the same without another walk.
        memory.index(ACME)!!.value.entries.map { it.digest }.assert().contains(new).doesNotContain(old)
        memory.index(ACME)!!.value.reclaiming.assert().contains(old)
    }

    @Test
    fun `a later publication does not keep the old branch below it`() = runBlocking<Unit> {
        headMovedBack()
        store.failNextSettle = true
        publish("new-branch")
        publish("third")

        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        policiesAt(2).assert().containsExactly("new-branch")
        revisions.rollbackCandidate(ACME, 2).policies.map { it.id.value }.assert().containsExactly("new-branch")
    }

    @Test
    fun `the repair says what it unlisted, once`() = runBlocking<Unit> {
        val old = headMovedBack()
        store.failNextSettle = true
        val new = publish("new-branch")
        appender.forget()

        revisions.history(ACME)
        revisions.history(ACME)

        val said = warnings().filter { "reclaimed" in it }
        said.size.assert().isEqualTo(1)
        said.single().assert().contains(ACME.toString()).contains("revision 2").contains(old.toString())
            .contains(new.toString())
    }

    @Test
    fun `an index that agrees with the head is returned without reading a record`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        publish("c")
        store.recordReads.set(0)

        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L)

        store.recordReads.get().assert().isEqualTo(0)
    }

    /**
     * The write path never replaces a listed number with another digest: only a repair, which has walked from the head,
     * does. So a settle of an old branch cannot put its entry over a right one, and a later publication lists itself
     * beside the old entry, which the next read replaces.
     */
    @Test
    fun `a publication leaves a number listed with another digest as it is`() = runBlocking<Unit> {
        val old = headMovedBack()
        val new = publish("new-branch")
        publish("third")

        val index = memory.index(ACME)!!.value
        index.entries.single { it.revision == 2L }.digest.assert().isEqualTo(old)
        index.entries.map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        revisions.history(ACME).single { it.revision == 2L }.digest.assert().isEqualTo(new)
        memory.index(ACME)!!.value.reclaiming.assert().containsExactly(old)
    }

    /** The same digest is written again, which gives an entry an older version wrote its parent. */
    @Test
    fun `an entry written without a parent is rewritten with it by a read`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        val index = memory.index(ACME)!!
        memory.putIndex(
            ACME,
            index.digest,
            index.value.copy(entries = index.value.entries.map { it.copy(parent = null) })
        )
        val parents = revisions.history(ACME).map { it.parent }

        parents[1].assert().isEqualTo(revisions.history(ACME)[0].digest)
        memory.index(ACME)!!.value.entries.drop(1).all { it.parent != null }.assert().isTrue()
    }

    /** A walk that ends at a missing record returns what it found and lists nothing of what it cannot judge (a). */
    @Test
    fun `a record missing below the head hides what cannot be shown to be on the chain`() = runBlocking<Unit> {
        val digests = (1..3).map { publish("p$it") }
        val index = memory.index(ACME)!!
        val bogus = SnapshotDigest(ByteArray(SnapshotDigest.SHA_256_BYTES) { 9 })
        memory.putIndex(
            ACME,
            index.digest,
            index.value.copy(
                entries = index.value.entries.map { if (it.revision == 2L) it.copy(digest = bogus) else it }
            ),
        )
        memory.removeRecord(ACME, digests[1])
        appender.forget()

        revisions.history(ACME).map { it.revision }.assert().containsExactly(3L)
        revisions.history(ACME)

        revisions.revision(ACME, 2).assert().isNull()
        val stored = memory.index(ACME)!!.value
        stored.entries.map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        stored.reclaiming.assert().isEmpty()
        warnings().filter { "is missing" in it }.size.assert().isEqualTo(1)
        warnings().first { "is missing" in it }.assert().contains("revision 2")
    }

    /** (b) A digest a repair replaced and that the head then returns to is listed again, and reclamation keeps it. */
    @Test
    fun `a head restored to a replaced digest is listed again and not reclaimed`() = runBlocking<Unit> {
        val first = publish("base")
        val old = publish("old-branch")
        memory.putHead(ACME, old, memory.record(ACME, first)!!)
        publish("new-branch")
        revisions.history(ACME)
        memory.index(ACME)!!.value.reclaiming.assert().contains(old)
        val head = memory.head(ACME)!!
        memory.putHead(ACME, head.digest, memory.record(ACME, old)!!)

        revisions.history(ACME).last().digest.assert().isEqualTo(old)
        memory.index(ACME)!!.value.reclaiming.assert().doesNotContain(old)
        clock.now = clock.now.plus(Duration.ofDays(2))
        revisions.reclaim(ACME)
        memory.record(ACME, old).assert().isNotNull()
        revisions.revision(ACME, 2)!!.policies.map { it.id.value }.assert().containsExactly("old-branch")
    }

    @Test
    fun `a republication of a number between the walk and the write is not overwritten`() = runBlocking<Unit> {
        headMovedBack()
        store.failNextSettle = true
        publish("new-branch")
        val other = SnapshotDigest(ByteArray(SnapshotDigest.SHA_256_BYTES) { 7 })
        store.beforeIndexWrite = {
            val current = memory.index(ACME)!!
            val entries = current.value.entries.map { if (it.revision == 2L) it.copy(digest = other) else it }
            memory.putIndex(ACME, current.digest, current.value.copy(entries = entries))
        }

        revisions.history(ACME)

        memory.index(ACME)!!.value.entries.single { it.revision == 2L }.digest.assert().isEqualTo(other)
    }

    /** #1265: the repair runs against the floor of its own attempt, so what reclamation took out does not come back. */
    @Test
    fun `a repair that a reclamation overtakes lists nothing below its floor`() = runBlocking<Unit> {
        (1..4).forEach { publish("p$it") }
        clock.now = clock.now.plus(Duration.ofDays(2))
        memory.dropIndex(ACME)
        store.afterRecordRead = { revisions.reclaim(ACME) }

        revisions.history(ACME)

        val index = memory.index(ACME)!!.value
        index.floor.assert().isEqualTo(3L)
        index.entries.map { it.revision }.assert().containsExactly(3L, 4L)
        revisions.history(ACME).map { it.revision }.assert().containsExactly(3L, 4L)
    }

    /**
     * The coordinator's counterexample to the write rule (#1536, review of 6b162cfc): only one failed index write, and
     * no read of the history in between. Revision 3 is refused the index (its parent entry is the old branch's), so
     * revision 4 finds its parent unlisted, is appended, and the top agrees with the head over an old entry of 2.
     */
    @Test
    fun `a revision published over an unlisted parent does not leave the old branch below a top that agrees`() =
        runBlocking<Unit> {
            headMovedBack()
            store.failNextSettle = true
            publish("new-branch")
            publish("third")
            publish("fourth")

            revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L, 4L)
            policiesAt(2).assert().containsExactly("new-branch")
        }

    /** A settle of the old branch that lands late, over a parent that is right, puts its entry back under a top that agrees. */
    @Test
    fun `a late settle of the old branch does not put its entry back under the new one`() = runBlocking<Unit> {
        publish("base")
        store.armed = true
        val late = async { publish("old-branch") }
        store.paused.await()
        // The head moves back to revision 1 while the old revision 2 is still being indexed.
        val first = memory.index(ACME)!!.value.entries.single().digest
        memory.putHead(ACME, memory.head(ACME)!!.digest, memory.record(ACME, first)!!)
            .assert().isInstanceOf(HeadWrite.Written::class.java)
        publish("new-branch")
        publish("third")
        store.resume.complete(Unit)
        late.await()

        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        policiesAt(2).assert().containsExactly("new-branch")
    }
}
