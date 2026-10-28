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

import me.ahoo.cosec.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Regressions for the round-2 review of the snapshot source (#991). */
class SnapshotRoundTwoTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1, "a1"))
        put(APP_B, document(1, "b1"))
        put(APP_C, document(1, "c1"))
    }

    /** Throws [error] from the callbacks named in [failing], and only from those. */
    private class ErrorMetrics(
        private val error: () -> Throwable,
        private vararg val failing: String
    ) : SnapshotMetrics {
        private fun maybeFail(event: String) {
            if (event in failing) throw error()
        }

        override fun onInstall(entry: SnapshotEntry<*>) = maybeFail("onInstall")

        override fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) =
            maybeFail("onRegression")

        override fun onDocumentLost(key: SnapshotKey, lastRevision: Long) = maybeFail("onDocumentLost")
    }

    private fun SnapshotSource<Compiled, Compiled>.policiesRevision(): Long? =
        (peek()?.policies as? DocumentState.Present)?.compiled?.revision

    @Test
    fun errorFromOnInstallLeavesTheKeyLoading() {
        testSource(store, metrics = ErrorMetrics({ AssertionError("meter") }, "onInstall")).use { source ->
            source.load().join().policiesCompiled.revision.assert().isEqualTo(1)
            store.put(SnapshotKey.Platform, document(2))
            source.policiesRevision().assert().isEqualTo(2)
            source.revalidate().get(5, TimeUnit.SECONDS)
            source.reload(SnapshotKey.Platform).get(5, TimeUnit.SECONDS).revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun errorFromOnRegressionLeavesTheKeyLoading() {
        store.put(SnapshotKey.Platform, document(5))
        testSource(store, metrics = ErrorMetrics({ AssertionError("meter") }, "onRegression")).use { source ->
            source.load().join()
            store.put(SnapshotKey.Platform, document(3))
            source.policiesRevision().assert().isEqualTo(3)
            store.put(SnapshotKey.Platform, document(4))
            source.policiesRevision().assert().isEqualTo(4)
        }
    }

    @Test
    fun errorFromOnDocumentLostStillFailsTheLostDocument() {
        testSource(store, metrics = ErrorMetrics({ AssertionError("meter") }, "onDocumentLost")).use { source ->
            source.load().join()
            store.remove(SnapshotKey.Platform)
            source.peek()!!.policies.assert().isInstanceOf(DocumentState.Failed::class.java)
            store.put(SnapshotKey.Platform, document(2))
            source.policiesRevision().assert().isEqualTo(2)
        }
    }

    @Test
    fun virtualMachineErrorFromOnDocumentLostStillFailsTheLostDocument() {
        testSource(store, metrics = ErrorMetrics({ StackOverflowError("meter") }, "onDocumentLost")).use { source ->
            source.load().join()
            store.remove(SnapshotKey.Platform)
            source.peek()!!.policies.assert().isInstanceOf(DocumentState.Failed::class.java)
        }
    }

    @Test
    fun virtualMachineErrorFromOnInstallLeavesTheKeyLoading() {
        testSource(store, metrics = ErrorMetrics({ StackOverflowError("meter") }, "onInstall")).use { source ->
            source.load().join().policies.assert().isInstanceOf(DocumentState.Present::class.java)
            store.put(SnapshotKey.Platform, document(2))
            source.policiesRevision().assert().isEqualTo(2)
            store.put(SnapshotKey.Platform, document(3))
            source.policiesRevision().assert().isEqualTo(3)
        }
    }

    @Test
    fun localCopyThatCannotBeConfirmedLooksAsOldAsItMayBe() {
        val clock = ManualClock()
        store.declaredLocalCopyMaxAge = Duration.ofSeconds(30)
        store.localCopies[SnapshotKey.Platform] = document(1)
        store.conditionalFailure = IllegalStateException("redis down")
        testSource(store, clock = clock).use { source ->
            source.load().join()

            source.peek()!!.lagAt(clock.now).assert().isEqualTo(Duration.ofSeconds(30))

            store.conditionalFailure = null
            source.revalidate().join()
            source.peek()!!.lagAt(clock.now).assert().isEqualTo(Duration.ZERO)
        }
    }

    @Test
    fun storeReadTimeoutDuringRevalidationIsNotAConfirmation() {
        val clock = ManualClock()
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, storeReadTimeout = Duration.ofMillis(50))
        testSource(store, options = options, clock = clock).use { source ->
            source.load().join()
            clock.advance(Duration.ofHours(1))
            store.hold = true

            source.revalidate().get(10, TimeUnit.SECONDS)

            source.peek()!!.lagAt(clock.now).assert().isEqualTo(Duration.ofHours(1))
        }
    }

    @Test
    fun reloadOfAnEvictedSlotReadsNothing() {
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 2, reloadConcurrency = 1)
        testSource(store, options = options).use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            source.peek("app-a")
            store.hold = true

            // The pass lists policies, app-a and app-b, and reloads them one at a time.
            val pass = source.revalidate()
            source.load("app-c")
            store.pending.first { it.key == APP_C }.let {
                store.pending.remove(it)
                it.release()
            }
            await { !source.holds(APP_B) }
            val readsBefore = store.readLog.count { it == APP_B }

            while (!pass.isDone) {
                store.releaseAll()
            }

            store.readLog.count { it == APP_B }.assert().isEqualTo(readsBefore)
        }
    }

    @Test
    fun slotEvictedBetweenLookupAndUseIsNeverServed() {
        testSource(store).use { source ->
            source.load("app-a").join()
            // A change the evicted slot will never hear about: a removed slot gets no notifications or revalidation.
            store.dropNotifications = true
            store.put(APP_A, document(2, "a2"))
            source.afterSlotLookup = { key ->
                source.afterSlotLookup = null
                source.evict(key).assert().isTrue()
            }

            val snapshot = source.load("app-a").join()

            snapshot.appCompiled.revision.assert().isEqualTo(2)
            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun evictedSlotIsNotRevalidatedNorPeeked() {
        testSource(store).use { source ->
            source.load("app-a").join()
            source.evict(APP_A).assert().isTrue()
            source.evict(APP_A).assert().isFalse()

            source.peek("app-a").assert().isNull()
            source.entries().map { it.key }.assert().doesNotContain(APP_A)
        }
    }

    @Test
    fun revalidationOfManyAppsDoesNotGrowTheStack() {
        val inMemory = InMemorySnapshotDocumentStore()
        inMemory.put(SnapshotKey.Platform, document(1))
        val apps = 10_000
        repeat(apps) { inMemory.put(SnapshotKey.App("app-$it"), document(1, "a")) }
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = apps * 2)
        testSource(inMemory, options = options).use { source ->
            repeat(apps) { source.load("app-$it").join() }

            source.revalidate().get(30, TimeUnit.SECONDS)

            source.entries().assert().hasSize(apps + 1)
        }
    }

    @Test
    fun rejectedReloadsBackOff() {
        val reject = AtomicBoolean()
        val compile = Executor { task ->
            if (reject.get()) throw RejectedExecutionException("full")
            task.run()
        }
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, rejectedReloadRetry = Duration.ofMillis(20))
        testSource(store, options = options, compileExecutor = compile).use { source ->
            source.load().join()
            reject.set(true)
            val before = store.conditionalReads.get()
            store.put(SnapshotKey.Platform, document(2))

            Thread.sleep(700)

            // Without backoff, 20 ms retries would read about 35 times; with doubling delays, about 5.
            val retries = store.conditionalReads.get() - before
            retries.assert().isBetween(3, 9)
            reject.set(false)
            await { source.policiesRevision() == 2L }
        }
    }

    @Test
    fun reloadFutureCompletesWhenNoLoadCanFollow() {
        val source = testSource(store)
        source.load().join()
        store.hold = true
        source.revalidate()
        val reloaded = source.reload(SnapshotKey.Platform)

        source.close()
        store.releaseAll()

        reloaded.get(5, TimeUnit.SECONDS).revision.assert().isEqualTo(1)
    }

    @Test
    fun closeAnswersWaitingRequestsAndStopsServing() {
        val source = testSource(store)
        source.load().join()
        store.hold = true
        val waiting = source.load("app-a")

        source.close()

        val error = assertThrows<ExecutionException> { waiting.get(5, TimeUnit.SECONDS) }
        error.cause.assert().isInstanceOf(IllegalStateException::class.java)
        source.peek().assert().isNull()
        val reads = store.readLog.size
        source.revalidate().isDone.assert().isTrue()
        store.readLog.assert().hasSize(reads)
    }
}
