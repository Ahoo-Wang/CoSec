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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class)

package me.ahoo.cosec.control.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.control.change.ALICE_OP
import me.ahoo.cosec.control.change.ChangeHarness
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * #1263 (`CTL-2`): the head write is the commit point. A store's own timeout after it is a failure of what follows the
 * write, not the caller's cancellation, and is not the publish's outcome. The caller's cancellation still reaches it.
 */
class CommittedRevisionTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    private class Store(private val memory: InMemoryControlStore) : ControlStore by memory {
        @Volatile
        var headWritten = false

        /** Whether the index writes, once the head is written, time out inside the store. */
        @Volatile
        var indexTimesOut = false

        /** Whether the app list writes, once the head is written, time out inside the store. */
        @Volatile
        var appsTimeOut = false

        /** Whether the change writes, once the head is written, time out inside the store. */
        @Volatile
        var changesTimeOut = false

        /** Whether the next record write waits, having told [paused]. */
        @Volatile
        var holdRecord = false

        /** Whether the next index write after the head write waits for [resume], having told [paused]. */
        @Volatile
        var holdIndex = false
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        private suspend fun timeOut(): Nothing = withTimeout(1L) { awaitCancellation() }

        override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest {
            if (holdRecord) {
                holdRecord = false
                paused.complete(Unit)
                resume.await()
            }
            return memory.putRecord(key, document)
        }

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite =
            memory.putHead(key, expected, document).also { headWritten = true }

        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
            if (headWritten && indexTimesOut) timeOut()
            if (headWritten && holdIndex) {
                holdIndex = false
                paused.complete(Unit)
                resume.await()
            }
            return memory.putIndex(key, expected, index)
        }

        override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean {
            if (headWritten && appsTimeOut) timeOut()
            return memory.putApps(expected, apps)
        }

        override suspend fun putChange(
            expected: SnapshotDigest?,
            change: me.ahoo.cosec.control.change.ChangeRequest,
        ): Boolean {
            if (headWritten && changesTimeOut) timeOut()
            return memory.putChange(expected, change)
        }
    }

    private val store = Store(memory)
    private val revisions = Revisions(store, clock)

    @Test
    fun `a store timeout indexing a written head still answers published - #1263`() = runBlocking<Unit> {
        store.indexTimesOut = true
        var settled: PublishOutcome.Published? = null

        val outcome = revisions.publish(ACME, null, candidate(ACME), publication()) { settled = it }

        val published = outcome as PublishOutcome.Published
        published.revision.assert().isEqualTo(1L)
        settled.assert().isSameAs(published)
        memory.head(ACME)!!.digest.assert().isEqualTo(published.digest)
        store.indexTimesOut = false
        revisions.history(ACME).map { it.revision }.assert().isEqualTo(listOf(1L))
    }

    @Test
    fun `a store timeout completing the app list still answers published - #1263`() = runBlocking<Unit> {
        store.appsTimeOut = true

        val outcome = revisions.publish(SnapshotKey.Platform, null, candidate(SnapshotKey.Platform), publication())

        (outcome as PublishOutcome.Published).revision.assert().isEqualTo(1L)
        memory.head(SnapshotKey.Platform).assert().isNotNull()
    }

    @Test
    fun `a caller cancelled before the head write is cancelled and writes no head - #1263`() = runBlocking<Unit> {
        store.holdRecord = true
        val publishing = async(start = CoroutineStart.UNDISPATCHED) {
            revisions.publish(ACME, null, candidate(ACME), publication())
        }
        store.paused.await()

        publishing.cancelAndJoin()

        publishing.isCancelled.assert().isTrue()
        memory.head(ACME).assert().isNull()
    }

    /** Past the head write the steps run to their end, and the cancellation is still there when they are done. */
    @Test
    fun `a caller cancelled after the head write stays cancelled, the revision settled - #1263`() = runBlocking<Unit> {
        store.holdIndex = true
        val publishing = async(start = CoroutineStart.UNDISPATCHED) {
            revisions.publish(ACME, null, candidate(ACME), publication())
            kotlinx.coroutines.yield()
        }
        store.paused.await()

        publishing.cancel()
        store.resume.complete(Unit)
        publishing.join()

        publishing.isCancelled.assert().isTrue()
        memory.head(ACME)!!.document.revision.assert().isEqualTo(1L)
        memory.index(ACME)!!.value.entries.map { it.revision }.assert().isEqualTo(listOf(1L))
    }

    /**
     * What follows the write of a change's revision is the same kind of failure: the answer says the revision is written
     * and how to settle it, never that the caller was cancelled, and the retry adds no revision.
     */
    @Test
    fun `a store timeout settling a published change says it is written, and a retry settles it - #1263`() =
        runBlocking<Unit> {
            val harness = ChangeHarness(clock, memory, store)
            val changes = harness.changes
            val change = ready(harness)
            store.changesTimeOut = true

            val answer = try {
                changes.publish(ALICE_OP, change.id)
                error("not refused")
            } catch (e: ChangeRejectedException) {
                e
            }

            answer.status.assert().isEqualTo(503)
            answer.message!!.assert().contains("revision 2").contains("is written")
            (answer.cause is TimeoutCancellationException || answer.cause is CancellationException).assert().isTrue()
            memory.head(ACME)!!.document.revision.assert().isEqualTo(2L)
            store.changesTimeOut = false
            val retried = changes.publish(ALICE_OP, change.id)
            retried.state.assert().isEqualTo(ChangeState.PUBLISHED)
            retried.publishedRevision.assert().isEqualTo(2L)
            memory.head(ACME)!!.document.revision.assert().isEqualTo(2L)
        }

    private suspend fun ready(harness: ChangeHarness): ChangeRequest {
        harness.revisions.publish(ACME, null, candidate(ACME), publication())
        store.headWritten = false
        val change = harness.changes.propose(ALICE_OP, ACME, 1, listOf(Operation.PutPolicy(allow("more"))), "more")
        harness.changes.skipShadow(ALICE_OP, change.id, "test").state.assert().isEqualTo(ChangeState.READY)
        return change
    }
}
