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
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** `CTL-84` (DR1-F-6): what a document's revision records may weigh, younger than `min-age` or not. */
class RecordBudgetTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)

    private suspend fun published(revisions: Revisions, count: Int) = repeat(count) {
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        revisions.publish(ACME, revisions.head(ACME), candidate(ACME), publication())
    }

    private suspend fun entries(): List<RevisionEntry> = store.index(ACME)!!.value.entries

    @Test
    fun `every entry this version writes names its record's stored size`() = runBlocking<Unit> {
        published(Revisions(store, clock), 2)

        entries().forEach { entry ->
            entry.size.assert().isEqualTo(store.record(ACME, entry.digest)!!.bodyBytes().size.toLong())
        }
    }

    @Test
    fun `past the byte budget the oldest records go, younger than min-age, the most recent always stay`() =
        runBlocking<Unit> {
            published(Revisions(store, clock), 1)
            // Each record weighs about this; two of them fill the budget, so nothing beyond the two most recent stays.
            val size = entries().single().size!!
            val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 2, documentCap = size + 16))
            published(revisions, 4)

            val reclaimed = revisions.reclaim(ACME)

            reclaimed.records.size.assert().isEqualTo(3)
            entries().map { it.revision }.assert().containsExactly(4L, 5L)
            store.recordDigests(ACME).size.assert().isEqualTo(2)
            revisions.reclaimedYoung.assert().isEqualTo(3L)
        }

    @Test
    fun `under the byte budget every record younger than min-age stays`() = runBlocking<Unit> {
        val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 2))
        published(revisions, 5)

        revisions.reclaim(ACME).records.assert().isEmpty()
        entries().size.assert().isEqualTo(5)
        revisions.reclaimedYoung.assert().isEqualTo(0L)
    }

    @Test
    fun `a record an open change rests on stays past the budget`() = runBlocking<Unit> {
        published(Revisions(store, clock), 1)
        val size = entries().single().size!!
        val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 2, documentCap = size + 16))
        published(revisions, 3)
        val base = entries().first { it.revision == 2L }.digest

        revisions.reclaim(ACME, keep = setOf(base))

        entries().map { it.revision }.assert().containsExactly(2L, 3L, 4L)
    }

    @Test
    fun `the byte budget counts the most recent too, so when every record is at the cap none older stays`() {
        val retention = RetentionPolicy(maxCount = 2, documentCap = 100)
        val entries = (1L..5L).map { RevisionEntry(it, digestOf(it), Instant.EPOCH, null, size = 100) }

        val over = RecordBudget(clock).over(entries, emptyMap(), retention)

        // 200 bytes in all, and the two most recent fill them. Counted beyond the most recent, two more would stay.
        over.assert().containsExactlyInAnyOrderElementsOf(entries.take(3).map { it.digest })
    }

    @Test
    fun `beyond the most recent the index lists at most the cap at the entry's bound`() {
        val retention = RetentionPolicy(maxCount = 2, documentCap = 2 * RetentionPolicy.INDEX_ENTRY_BYTES)
        val entries = (1L..10L).map { RevisionEntry(it, digestOf(it), Instant.EPOCH, null, size = 1) }

        val over = RecordBudget(clock).over(entries, emptyMap(), retention)

        // Two most recent, then two more: the six oldest go, however little they weigh.
        over.assert().containsExactlyInAnyOrderElementsOf(entries.take(6).map { it.digest })
    }

    @Test
    fun `an entry an older version wrote is sized from its record, once`() = runBlocking<Unit> {
        published(Revisions(store, clock), 3)
        val index = store.index(ACME)!!
        store.putIndex(ACME, index.digest, index.value.copy(entries = index.value.entries.map { it.copy(size = null) }))
        val reads = AtomicInteger()
        val counting = object : ControlStore by store {
            override suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument? =
                store.record(key, digest).also { reads.incrementAndGet() }
        }
        val revisions = Revisions(counting, clock, RetentionPolicy(maxCount = 1))

        revisions.reclaim(ACME)
        val first = reads.getAndSet(0)
        revisions.reclaim(ACME)

        entries().filter { it.revision < 3 }.forEach { it.size.assert().isNotNull() }
        // The first pass read the two records beyond the most recent once more than the second, which knows their sizes.
        (first - reads.get()).assert().isEqualTo(2)
    }

    @Test
    fun `the default cap is the change checks' and the index holds 26,214 entries beyond the most recent`() {
        RetentionPolicy.DEFAULT_DOCUMENT_CAP.assert().isEqualTo(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong())
        RetentionPolicy().maxEntries.assert().isEqualTo(26_214)
        RetentionPolicy().byteBudget.assert().isEqualTo(50L * 8 * 1024 * 1024)
    }

    private fun digestOf(n: Long) = SnapshotDigest("SHA-256", ByteArray(32).also { it[0] = n.toByte() })
}
