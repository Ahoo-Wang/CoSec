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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * UC-1's seam: each installed document goes to the [InstallReview]s on the housekeeping thread, after its load has been
 * answered, once per install. A review housekeeping refuses is offered again when a read confirms its revision, and
 * dropped when the revision leaves; a slow review delays no install, and one that throws leaves the install as it is.
 * A key that leaves the source is removed from them.
 */
class InstallReviewTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val compiler = FakeCompiler()
    private val housekeeping = SnapshotExecutors.newHousekeeping()
    private val full = CountDownLatch(1)

    private class Reviewed(val key: SnapshotKey, val revision: Long, val thread: String)

    private val reviewed = CopyOnWriteArrayList<Reviewed>()

    @AfterEach
    fun release() {
        full.countDown()
        housekeeping.shutdownNow()
    }

    private fun source(review: InstallReview<Compiled>): SnapshotSource<Compiled, Compiled> {
        val ticker = AtomicLong()
        return SnapshotSource(
            store,
            SnapshotKey.Platform,
            compiler,
            listOf(SnapshotFamily(SnapshotKind.APP, compiler, OPTIONS.maxApps, OPTIONS.maxAbsent)),
            OPTIONS,
            ManualClock(),
            SnapshotMetrics.NOOP,
            directExecutor,
            null,
            { ticker.incrementAndGet() },
            housekeeping,
            keyRunners(),
            listOf(review),
        )
    }

    private val recording = InstallReview<Compiled> { key, revision, _ ->
        reviewed += Reviewed(key, revision, Thread.currentThread().name)
    }

    private fun of(key: SnapshotKey) = reviewed.filter { it.key == key }

    /** Housekeeping's thread waits on [full], and its queue is full: every task offered now is refused. */
    private fun fill() {
        val running = CountDownLatch(1)
        housekeeping.execute {
            running.countDown()
            full.await(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
        }
        running.await(TEST_LIMIT_SECONDS, TimeUnit.SECONDS).assert().isTrue()
        repeat(SnapshotExecutors.HOUSEKEEPING_QUEUE) { housekeeping.execute {} }
    }

    private fun empty() {
        full.countDown()
        awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
    }

    @Test
    fun `an installed revision is reviewed once, on housekeeping, after its load is answered`() {
        val held = CountDownLatch(1)
        val review = InstallReview<Compiled> { key, revision, compiled ->
            if (key == APP_A) held.await(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            recording.installed(key, revision, compiled)
        }
        source(review).use { source ->
            // The load is answered while its review waits: nothing a request waits on runs it.
            loadedRevision(source).assert().isEqualTo(1)
            of(APP_A).assert().isEmpty()
            held.countDown()
            awaitCondition { of(APP_A).isNotEmpty() }
            of(APP_A).single().thread.assert().startsWith(HOUSEKEEPING)
            // A revalidation that finds the same revision confirms it: no install, no review.
            source.revalidate().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
            of(APP_A).size.assert().isEqualTo(1)
            // A forced reload installs the same revision again, and offers it again: one review per revision is the
            // reviewer's to remember.
            source.reload(APP_A).get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { of(APP_A).size == 2 }
            store.put(APP_A, document(2))
            source.revalidate().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { of(APP_A).size == 3 }
            of(APP_A).map { it.revision }.assert().containsExactly(1L, 1L, 2L)
        }
    }

    @Test
    fun `a review housekeeping refused is offered again when the key is next read`() {
        source(recording).use { source ->
            source.load().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { source.quiescent() }
            fill()
            source.loadAll(listOf(APP_A)).get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            empty()
            of(APP_A).assert().isEmpty()
            // The pass confirms revision 1 and installs nothing: what reviews it is the read that pass starts.
            source.revalidate().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { of(APP_A).isNotEmpty() }
            awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
            of(APP_A).single().revision.assert().isEqualTo(1)
            // Reviewed now, it is not offered again by the next pass.
            source.revalidate().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
            of(APP_A).size.assert().isEqualTo(1)
        }
    }

    @Test
    fun `a refused review of a revision no longer installed is dropped`() {
        source(recording).use { source ->
            source.load().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { source.quiescent() }
            fill()
            source.loadAll(listOf(APP_A)).get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            empty()
            // The document goes: the next read installs the key as failed (the store lost it), not revision 1.
            store.remove(APP_A)
            source.revalidate().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            source.peekAll(listOf(APP_A))!!.entry(APP_A)!!.state.assert().isInstanceOf(DocumentState.Failed::class.java)
            source.revalidate().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
            of(APP_A).assert().isEmpty()
        }
    }

    @Test
    fun `a slow review delays no install`() {
        val held = CountDownLatch(1)
        val slow = InstallReview<Compiled> { key, revision, compiled ->
            // Longer than any wait of the test: a review on the load's path fails it rather than races it.
            held.await(TEST_LIMIT_SECONDS * 3, TimeUnit.SECONDS)
            recording.installed(key, revision, compiled)
        }
        source(slow).use { source ->
            loadedRevision(source).assert().isEqualTo(1)
            // The review of revision 1 holds housekeeping; revision 2 installs and is served all the same.
            store.put(APP_A, document(2))
            source.revalidate().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            source.peekAll(listOf(APP_A))!!.entry(APP_A)!!.revision.assert().isEqualTo(2)
            of(APP_A).assert().isEmpty()
            held.countDown()
            awaitCondition { of(APP_A).size == 2 }
            of(APP_A).map { it.revision }.assert().containsExactly(1L, 2L)
        }
    }

    @Test
    fun `a key that leaves the source is removed from the reviews, after its review`() {
        val events = CopyOnWriteArrayList<String>()
        val review = object : InstallReview<Compiled> {
            override fun installed(key: SnapshotKey, revision: Long, compiled: Compiled) {
                events += "installed $key $revision"
            }

            override fun removed(key: SnapshotKey) {
                events += "removed $key ${Thread.currentThread().name.startsWith(HOUSEKEEPING)}"
            }
        }
        source(review).use { source ->
            loadedRevision(source).assert().isEqualTo(1)
            source.evict(APP_A).assert().isTrue()
            awaitCondition { events.any { it.startsWith("removed $APP_A") } }
            // Installed back, it is reviewed again.
            loadedRevision(source).assert().isEqualTo(1)
            awaitCondition { events.count { it.startsWith("installed $APP_A") } == 2 }
            events.filter { APP_A.toString() in it }.assert()
                .containsExactly("installed $APP_A 1", "removed $APP_A true", "installed $APP_A 1")
        }
    }

    @Test
    fun `a review that throws leaves the install as it is`() {
        val failing = InstallReview<Compiled> { _, _, _ -> throw IllegalStateException("broken review") }
        source(failing).use { source ->
            loadedRevision(source).assert().isEqualTo(1)
            awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
            source.peekAll(listOf(APP_A))!!.entry(APP_A)!!.revision.assert().isEqualTo(1)
        }
    }

    private fun loadedRevision(source: SnapshotSource<Compiled, Compiled>): Long =
        source.loadAll(listOf(APP_A)).get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS).entry(APP_A)!!.revision

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_LIMIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold within $TEST_LIMIT_SECONDS s." }
            Thread.onSpinWait()
        }
    }

    private companion object {
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
        const val HOUSEKEEPING = "cosec-snapshot-housekeeping"
        const val TEST_LIMIT_SECONDS = 10L
    }
}
