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
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
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
    fun `an index that ends before the head, or at another digest, differs`() = runBlocking<Unit> {
        publish("a")
        publish("b")
        rewriteIndex { it.copy(entries = it.entries.dropLast(1)) }
        (revisions.verifyIndex(ACME) as IndexCheck.Differs).why.assert().contains("the index ends at revision 1")
        rewriteIndex { RevisionIndex.EMPTY }
        (revisions.verifyIndex(ACME) as IndexCheck.Differs).why.assert().contains("lists no revision")
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
