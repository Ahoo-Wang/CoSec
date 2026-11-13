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
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * L6 of the review of #1240: the digest a revision had, which `explain` at revisions and a rollback ask first, is found
 * from the revision index and the head's envelope; the head is decoded only when the chain has to be walked from it.
 * The head here cannot be decoded, at the revision the index lists, so only a decode would notice.
 */
class RetainedHistoryTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)

    private suspend fun published(count: Int) = repeat(count) {
        clock.now = clock.now.plus(Duration.ofDays(1))
        revisions.publish(ACME, revisions.head(ACME), candidate(ACME), publication())
    }

    /** The head replaced by bytes the codec refuses, at the same revision. */
    private suspend fun unreadableHead() {
        val head = store.head(ACME)!!
        val document = head.document
        store.putHead(
            ACME,
            head.digest,
            SnapshotDocument(
                document.schema,
                document.revision,
                document.publishedAt,
                "not a document".encodeToByteArray()
            ),
        )
    }

    @Test
    fun `the digest of a retained revision and the history are read without decoding the head - L6 of #1240`() =
        runBlocking<Unit> {
            published(3)
            val entries = revisions.history(ACME)
            unreadableHead()

            revisions.history(ACME).assert().isEqualTo(entries)
            revisions.retainedDigest(ACME, 2).assert().isEqualTo(entries[1].digest)
            revisions.retainedDigest(ACME, 3).assert().isEqualTo(entries[2].digest)
            revisions.retainedDigest(ACME, 4).assert().isNull()
        }

    @Test
    fun `a head the index is behind is decoded, and its chain walked - L6 of #1240`() = runBlocking<Unit> {
        published(3)
        store.dropIndex(ACME)

        revisions.history(ACME).map { it.revision }.assert().containsExactly(1, 2, 3)
        store.dropIndex(ACME)
        unreadableHead()

        assertThrownBy<SnapshotFormatException> { runBlocking { revisions.history(ACME) } }
    }
}
