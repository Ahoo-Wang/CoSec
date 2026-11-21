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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `verifyIndex` (CTL-35) says what [Revisions.history] checks before it returns the index, and repairs nothing. */
class VerifyIndexTest {
    private val memory = InMemoryControlStore()
    private val revisions = Revisions(memory)

    private suspend fun publish(policyId: String): SnapshotDigest {
        val document = candidate(ACME, listOf(policy(policyId)))
        return (revisions.publish(ACME, revisions.head(ACME), document, publication()) as PublishOutcome.Published)
            .digest
    }

    private suspend fun rewriteIndex(change: (RevisionIndex) -> RevisionIndex) {
        val index = memory.index(ACME)!!
        memory.putIndex(ACME, index.digest, change(index.value))
    }

    private suspend fun rewriteEntries(change: (RevisionEntry) -> RevisionEntry) =
        rewriteIndex { index -> index.copy(entries = index.entries.map(change)) }

    @Test
    fun `an index of ordinary publications follows the head, and so does no document at all`() = runBlocking<Unit> {
        revisions.verifyIndex(ACME).assert().isSameAs(IndexCheck.Follows)
        publish("a")
        publish("b")
        revisions.verifyIndex(ACME).assert().isSameAs(IndexCheck.Follows)
    }

    @Test
    fun `an index one revision behind the head is a publication settling, two behind differs`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        publish("c")
        rewriteIndex { it.copy(entries = it.entries.dropLast(1)) }
        (revisions.verifyIndex(ACME) as IndexCheck.Behind).revision.assert().isEqualTo(3L)
        rewriteIndex { it.copy(entries = it.entries.dropLast(1)) }
        (revisions.verifyIndex(ACME) as IndexCheck.Differs).why.assert().contains("the index ends at revision 1")
    }

    @Test
    fun `an empty index is a first publication settling, or a lost index`() = runBlocking<Unit> {
        publish("a")
        rewriteIndex { RevisionIndex.EMPTY }
        (revisions.verifyIndex(ACME) as IndexCheck.Behind).revision.assert().isEqualTo(1L)
        publish("b")
        rewriteIndex { RevisionIndex.EMPTY }
        (revisions.verifyIndex(ACME) as IndexCheck.Differs).why.assert().contains("lists no revision")
    }

    /** R2C-19: the history read, `verifyIndex` and the retention pre-check judge the index through one function. */
    @Test
    fun `an entry above the head is left out by every reader of the index alike`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        rewriteIndex { it.copy(entries = it.entries + it.entries.last().copy(revision = 9)) }
        val head = memory.head(ACME)!!
        val index = memory.index(ACME)!!.value

        HistoryRepair.entriesUpTo(index, head).map { it.revision }.assert().containsExactly(1L, 2L)
        HistoryRepair.agrees(index, head).assert().isTrue()
        revisions.verifyIndex(ACME).assert().isSameAs(IndexCheck.Follows)
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L)
    }

    /** The latches of `LateSettlementTest`: a verification between a publication's head write and its index write. */
    @Test
    fun `a verification between a head write and its index write finds a note, not a finding`() = runBlocking<Unit> {
        publish("a")
        val latched = LatchedStore(memory)
        val racing = Revisions(latched)
        latched.armed = true
        val late = async {
            racing.publish(ACME, racing.head(ACME), candidate(ACME, listOf(policy("b"))), publication())
        }
        latched.paused.await()

        (racing.verifyIndex(ACME) as IndexCheck.Behind).revision.assert().isEqualTo(2L)
        latched.resume.complete(Unit)
        late.await()
        racing.verifyIndex(ACME).assert().isSameAs(IndexCheck.Follows)
    }

    /** Holds the index write that follows the head write of a publication once [armed], until [resume]. */
    private class LatchedStore(private val memory: InMemoryControlStore) : ControlStore by memory {
        var armed = false
        private var written = false
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument) =
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

    @Test
    fun `an index with another digest at the head's revision, or none at all, differs`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        val other = SnapshotDigest(ByteArray(SnapshotDigest.SHA_256_BYTES) { 3 })
        rewriteEntries { if (it.revision == 2L) it.copy(digest = other) else it }
        val differs = revisions.verifyIndex(ACME) as IndexCheck.Differs
        differs.why.assert().contains("the index ends at revision 2 ($other)")
        memory.dropIndex(ACME)
        (revisions.verifyIndex(ACME) as IndexCheck.Differs).why.assert().contains("lists no revision")
    }

    @Test
    fun `a link that is broken differs, and an entry with no parent only cannot be checked`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        publish("c")
        val other = SnapshotDigest(ByteArray(SnapshotDigest.SHA_256_BYTES) { 5 })
        rewriteEntries { if (it.revision == 3L) it.copy(parent = other) else it }
        (revisions.verifyIndex(ACME) as IndexCheck.Differs).why.assert().contains("revision 3 names $other")
        rewriteEntries { it.copy(parent = null) }
        (revisions.verifyIndex(ACME) as IndexCheck.Unchecked).entries.assert().isEqualTo(2)
    }

    /** It never writes: the repair is a read of the history. */
    @Test
    fun `verifying the index repairs nothing, a read of the history does`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        rewriteIndex { it.copy(entries = it.entries.dropLast(1)) }
        val before = memory.index(ACME)!!.digest

        revisions.verifyIndex(ACME)
        memory.index(ACME)!!.digest.assert().isEqualTo(before)

        revisions.history(ACME)
        revisions.verifyIndex(ACME).assert().isSameAs(IndexCheck.Follows)
    }
}
