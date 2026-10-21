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

package me.ahoo.cosec.snapshot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SnapshotSourceTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Policies, document(1))
        put(APP_A, document(1, "a1"))
    }

    @Test
    fun peekIsEmptyUntilLoaded() {
        testSource(store).use { source ->
            source.peek().assert().isNull()
            source.peek("app-a").assert().isNull()
            store.reads.get().assert().isZero()
        }
    }

    @Test
    fun loadInstallsAndPeekReturnsTheSameEntries() {
        val metrics = RecordingMetrics()
        testSource(store, metrics = metrics).use { source ->
            val loaded = source.load("app-a").join()
            loaded.policiesCompiled.entries.assert().containsExactly("p1")
            loaded.appCompiled.entries.assert().containsExactly("a1")
            loaded.version.assert().isEqualTo(SnapshotVersion(1, 1))

            val peeked = source.peek("app-a")!!
            peeked.policiesEntry.assert().isSameAs(loaded.policiesEntry)
            peeked.appEntry.assert().isSameAs(loaded.appEntry)
            metrics.loads.assert().contains(SnapshotKind.APP to LoadOutcome.COLD, SnapshotKind.APP to LoadOutcome.HIT)
            metrics.installs.map { it.key }.assert().containsExactlyInAnyOrder(SnapshotKey.Policies, APP_A)
        }
    }

    @Test
    fun peekWithoutAppAllocatesNoSnapshot() {
        testSource(store).use { source ->
            source.load().join()
            val first = source.peek()!!
            first.assert().isSameAs(source.peek())
            first.app.assert().isNull()
            first.version.assert().isEqualTo(SnapshotVersion(1, 0))
            source.load().join().assert().isSameAs(first)
        }
    }

    @Test
    fun absentAppIsAbsent() {
        testSource(store).use { source ->
            val snapshot = source.load("app-b").join()
            snapshot.app.assert().isEqualTo(DocumentState.Absent)
            snapshot.version.assert().isEqualTo(SnapshotVersion(1, 0))
            source.peek("app-b")!!.app.assert().isEqualTo(DocumentState.Absent)
        }
    }

    @Test
    fun absentAppBecomesPresentWhenPublished() {
        testSource(store).use { source ->
            source.load("app-b").join()
            store.put(APP_B, document(1, "b1"))
            source.peek("app-b")!!.appCompiled.entries.assert().containsExactly("b1")
        }
    }

    @Test
    fun invalidAppIdIsRejected() {
        testSource(store).use { source ->
            assertThrows<IllegalArgumentException> { source.load("bad:app") }
            assertThrows<IllegalArgumentException> { source.load("") }
        }
    }

    @Test
    fun changeNotificationInstallsTheNewRevision() {
        testSource(store).use { source ->
            source.load("app-a").join()
            val conditionalReads = store.conditionalReads.get()
            store.put(SnapshotKey.Policies, document(2))
            store.put(APP_A, document(2, "a2"))
            val snapshot = source.peek("app-a")!!
            snapshot.version.assert().isEqualTo(SnapshotVersion(2, 2))
            snapshot.appCompiled.entries.assert().containsExactly("a2")
            store.conditionalReads.get().assert().isEqualTo(conditionalReads + 2)
        }
    }

    @Test
    fun notificationForAnAppNotHeldReadsNothing() {
        testSource(store).use { source ->
            source.load().join()
            val reads = store.reads.get()
            val conditionalReads = store.conditionalReads.get()
            store.put(APP_B, document(1, "b1"))
            store.reads.get().assert().isEqualTo(reads)
            store.conditionalReads.get().assert().isEqualTo(conditionalReads)
            source.peek("app-b").assert().isNull()
        }
    }

    @Test
    fun revalidateConfirmsWithConditionalReads() {
        val clock = ManualClock()
        testSource(store, clock = clock).use { source ->
            source.load("app-a").join()
            val loadedAt = clock.now
            val conditionalReads = store.conditionalReads.get()
            clock.advance(Duration.ofSeconds(30))
            source.peek("app-a")!!.lagAt(clock.now).assert().isEqualTo(Duration.ofSeconds(30))

            source.revalidate().join()

            store.reads.get().assert().isEqualTo(2)
            store.conditionalReads.get().assert().isEqualTo(conditionalReads + 2)
            source.entries().forEach { it.confirmedAt.assert().isEqualTo(loadedAt.plusSeconds(30)) }
            source.peek("app-a")!!.lagAt(clock.now).assert().isEqualTo(Duration.ZERO)
        }
    }

    @Test
    fun revalidateWithNothingInstalledCompletes() {
        testSource(store).use { source ->
            source.revalidate().isDone.assert().isTrue()
        }
    }

    @Test
    fun singleFlightSharesOneReadAndCompilation() {
        val compiler = FakeCompiler()
        val gate = CountDownLatch(1)
        compiler.gate = gate
        val callers = 16
        val pool = Executors.newFixedThreadPool(callers)
        testSource(store, compiler = compiler, compileExecutor = null).use { source ->
            val barrier = CyclicBarrier(callers)
            val futures = (1..callers).map {
                CompletableFuture.supplyAsync({
                    barrier.await()
                    source.load("app-a")
                }, pool)
            }.map { it.get(10, TimeUnit.SECONDS) }
            gate.countDown()
            val snapshots = futures.map { it.get(10, TimeUnit.SECONDS) }

            store.reads.get().assert().isEqualTo(2)
            compiler.compiled.assert().hasSize(2)
            snapshots.map { it.appEntry }.distinct().assert().hasSize(1)
            snapshots.map { it.policiesEntry }.distinct().assert().hasSize(1)
        }
        pool.shutdown()
    }

    @Test
    fun leastRecentlyUsedAppIsEvicted() {
        store.put(APP_B, document(1, "b1"))
        store.put(APP_C, document(1, "c1"))
        testSource(store, options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 2)).use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            source.peek("app-a").assert().isNotNull()

            source.load("app-c").join()

            await { !source.holds(APP_B) }
            source.peek("app-a").assert().isNotNull()
            source.peek("app-c").assert().isNotNull()
            source.peek().assert().isNotNull()
            source.entries().map { it.key }.assert().containsExactlyInAnyOrder(SnapshotKey.Policies, APP_A, APP_C)

            source.load("app-b").join().appCompiled.entries.assert().containsExactly("b1")
        }
    }

    @Test
    fun failedColdLoadOfAnUnknownAppLeavesNoSlot() {
        testSource(store).use { source ->
            source.load().join()
            store.failure = IllegalStateException("down")
            source.load("app-z").join().app.assert().isInstanceOf(DocumentState.Failed::class.java)
            source.entries().map { it.key }.assert().containsExactly(SnapshotKey.Policies)
            store.failure = null
            source.load("app-z").join().app.assert().isEqualTo(DocumentState.Absent)
        }
    }

    @Test
    fun prewarmMakesTheSourceReady() {
        testSource(store).use { source ->
            source.isPrewarmed.assert().isFalse()
            source.prewarm(listOf(SnapshotKey.Policies, APP_A)).get(5, TimeUnit.SECONDS)
            source.isPrewarmed.assert().isTrue()
            source.peek("app-a").assert().isNotNull()
            store.reads.get().assert().isEqualTo(2)
            source.prewarm(listOf(SnapshotKey.Policies)).get(5, TimeUnit.SECONDS)
            store.reads.get().assert().isEqualTo(2)
        }
    }

    @Test
    fun prewarmRetriesWithBackoffUntilItSucceeds() {
        val options = SnapshotOptions(
            revalidateInterval = Duration.ZERO,
            prewarmInitialBackoff = Duration.ofMillis(10),
            prewarmMaxBackoff = Duration.ofMillis(20),
        )
        testSource(store, options = options).use { source ->
            store.failure = IllegalStateException("down")
            val prewarm = source.prewarm(listOf(SnapshotKey.Policies, APP_A))
            await { store.reads.get() >= 6 }
            prewarm.isDone.assert().isFalse()
            source.isPrewarmed.assert().isFalse()

            store.failure = null
            prewarm.get(5, TimeUnit.SECONDS)
            source.isPrewarmed.assert().isTrue()
        }
    }

    @Test
    fun prewarmStopsWhenClosed() {
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, prewarmInitialBackoff = Duration.ofMillis(10))
        val source = testSource(store, options = options)
        store.failure = IllegalStateException("down")
        val prewarm = source.prewarm(listOf(SnapshotKey.Policies))
        source.close()
        val error = assertThrows<ExecutionException> { prewarm.get(5, TimeUnit.SECONDS) }
        error.cause.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun reloadLoadsAKeyThatIsNotInstalled() {
        testSource(store).use { source ->
            source.reload(APP_A).join().revision.assert().isEqualTo(1)
            source.reload(SnapshotKey.Policies).join().revision.assert().isEqualTo(1)
            source.peek("app-a").assert().isNotNull()
        }
    }

    @Test
    fun closeUnsubscribesAndRefusesLoads() {
        val source = testSource(store)
        store.listenerCount.assert().isEqualTo(1)
        source.close()
        source.close()
        store.listenerCount.assert().isZero()
        val error = assertThrows<ExecutionException> { source.load().get() }
        error.cause.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun periodicRevalidationRunsOnItsOwn() {
        val options = SnapshotOptions(revalidateInterval = Duration.ofMillis(20))
        SnapshotSource(store, FakeCompiler(), FakeCompiler(), options).use { source ->
            source.load().join()
            val afterLoad = store.conditionalReads.get()
            await { store.conditionalReads.get() >= afterLoad + 2 }
        }
    }

    @Test
    fun lagIsReportedAboveTheThresholds() {
        val clock = ManualClock()
        val metrics = RecordingMetrics()
        testSource(store, clock = clock, metrics = metrics).use { source ->
            source.load().join()
            source.reportLag()
            metrics.lagExceeded.assert().isEmpty()

            store.failure = IllegalStateException("down")
            clock.advance(Duration.ofSeconds(61))
            source.tick().join()
            source.reportLag()
            clock.advance(Duration.ofMinutes(5))
            source.reportLag()

            // The second report within lagLogInterval is throttled; the escalation comes after it.
            metrics.lagExceeded.map { it.third }.assert().containsExactly(false, true)
            metrics.lagExceeded.last().second.assert().isEqualTo(Duration.ofSeconds(361))

            store.failure = null
            source.revalidate().join()
            source.reportLag()
            metrics.lagExceeded.assert().hasSize(2)
        }
    }

    @Test
    fun failedEntriesAreNotReportedAsLagging() {
        val clock = ManualClock()
        val metrics = RecordingMetrics()
        store.undecodable = true
        testSource(store, clock = clock, metrics = metrics).use { source ->
            source.load().join()
            clock.advance(Duration.ofHours(1))
            source.reportLag()
            metrics.lagExceeded.assert().isEmpty()
        }
    }

    companion object {
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Condition not met within 10 seconds." }
                Thread.sleep(5)
            }
        }
    }
}
