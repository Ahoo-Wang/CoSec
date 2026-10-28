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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind

import me.ahoo.cosec.runtime.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The main behaviours again, on the real compile pool and scheduler instead of the direct executor most tests use:
 * installs happen on other threads, so every assertion waits for the state it expects instead of relying on order.
 */
class SnapshotRealPoolTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1, "a1"))
        put(APP_B, document(1, "b1"))
    }

    private fun SnapshotSource<Compiled, Compiled>.revisionOf(appId: String? = null): Long? {
        val snapshot = peek(appId) ?: return null
        val state = if (appId == null) snapshot.policies else snapshot.app
        return (state as? DocumentState.Present)?.compiled?.revision
    }

    @Test
    fun notificationsInstallNewRevisions() {
        testSource(store, compileExecutor = null).use { source ->
            source.load("app-a").get(10, TimeUnit.SECONDS)
            store.put(SnapshotKey.Platform, document(2))
            store.put(APP_A, document(2, "a2"))
            await { source.revisionOf() == 2L && source.revisionOf("app-a") == 2L }
        }
    }

    @Test
    fun regressionIsInstalledAndReported() {
        val metrics = RecordingMetrics()
        store.put(SnapshotKey.Platform, document(5))
        testSource(store, metrics = metrics, compileExecutor = null).use { source ->
            source.load().get(10, TimeUnit.SECONDS)
            store.put(SnapshotKey.Platform, document(3))
            await { source.revisionOf() == 3L }
            metrics.regressions.assert().containsExactly(Triple(SnapshotKey.Platform, 5L, 3L))
        }
    }

    @Test
    fun lostDocumentFailsClosed() {
        testSource(store, compileExecutor = null).use { source ->
            source.load("app-a").get(10, TimeUnit.SECONDS)
            store.remove(APP_A)
            await { source.peek("app-a")?.app is DocumentState.Failed }
        }
    }

    @Test
    fun evictionKeepsAtMostMaxApps() {
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 1)
        testSource(store, options = options, compileExecutor = null).use { source ->
            source.load("app-a").get(10, TimeUnit.SECONDS)
            source.load("app-b").get(10, TimeUnit.SECONDS)
            await { source.entries().count { it.key is SnapshotKey.App } == 1 }
            source.load("app-a").get(10, TimeUnit.SECONDS).appCompiled.revision.assert().isEqualTo(1)
        }
    }

    @Test
    fun resetReloadsEverything() {
        testSource(store, compileExecutor = null).use { source ->
            source.load("app-a").get(10, TimeUnit.SECONDS)
            store.dropNotifications = true
            store.put(SnapshotKey.Platform, document(2))
            store.put(APP_A, document(2, "a2"))
            store.reset()
            await { source.revisionOf() == 2L && source.revisionOf("app-a") == 2L }
        }
    }

    /**
     * A closed source does no more work: close returns only after what was running on its own pools has finished. The
     * compilation is held until close starts; then it waits, at most [CLOSE_SIGNAL_MILLIS], for close to return. Were
     * close not to wait for it, close would return first, every time.
     */
    @Test
    fun closeWaitsForTheSourcesOwnThreads() {
        val events = CopyOnWriteArrayList<String>()
        val closeReturned = CountDownLatch(1)
        val compiler = FakeCompiler().apply { gate = CountDownLatch(1) }
        store.onUnsubscribe = { compiler.gate!!.countDown() }
        val metrics = object : SnapshotMetrics {
            override fun onCompile(kind: SnapshotKind, duration: Duration, succeeded: Boolean) {
                closeReturned.await(CLOSE_SIGNAL_MILLIS, TimeUnit.MILLISECONDS)
                events += "work done"
            }
        }
        val source = testSource(store, compiler, metrics = metrics, compileExecutor = null)
        source.load()
        await { compiler.threads.isNotEmpty() }
        source.close()
        events += "close returned"
        closeReturned.countDown()
        await { events.size == 2 }
        events.assert().containsExactly("work done", "close returned")
        source.threadsTerminated.assert().isTrue()
    }

    /** Closing from one of the source's own threads (here, an install callback) does not wait for itself. */
    @Test
    fun closeOnTheSourcesOwnThreadDoesNotWaitForItself() {
        val closed = CompletableFuture<Duration>()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val metrics = object : SnapshotMetrics {
            override fun onInstall(entry: SnapshotEntry<*>) {
                val start = System.nanoTime()
                source.close()
                closed.complete(Duration.ofNanos(System.nanoTime() - start))
            }
        }
        source = testSource(store, metrics = metrics, compileExecutor = null)
        // The load it closes during fails as closed; what matters is that close returned without waiting for itself.
        source.load()
        closed.get(10, TimeUnit.SECONDS).assert().isLessThan(SnapshotSource.CLOSE_TIMEOUT)
    }

    /** Work that outlives the close timeout: close logs it and returns, leaving the source's threads to finish. */
    @Test
    fun closeGivesUpAfterItsTimeout() {
        val compiler = FakeCompiler().apply { gate = CountDownLatch(1) }
        val source = testSource(store, compiler, compileExecutor = null)
        source.closeTimeout = Duration.ofMillis(SHORT_TIMEOUT_MILLIS)
        source.load()
        await { compiler.threads.isNotEmpty() }
        source.close()
        source.threadsTerminated.assert().isFalse()
        compiler.gate!!.countDown()
        await { source.threadsTerminated }
    }

    /** An interrupted closer stops waiting at once and keeps its interrupt flag. */
    @Test
    fun anInterruptedCloseKeepsTheInterrupt() {
        val compiler = FakeCompiler().apply { gate = CountDownLatch(1) }
        val source = testSource(store, compiler, compileExecutor = null)
        source.load()
        await { compiler.threads.isNotEmpty() }
        Thread.currentThread().interrupt()
        source.close()
        Thread.interrupted().assert().isTrue()
        source.threadsTerminated.assert().isFalse()
        compiler.gate!!.countDown()
        await { source.threadsTerminated }
    }

    /** With the compile pool and scheduler given to it, close waits only for the housekeeping it owns. */
    @Test
    fun closeWaitsOnlyForThePoolsItOwns() {
        val scheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1)
        try {
            val source = testSource(store, scheduler = scheduler)
            source.load().get(10, TimeUnit.SECONDS)
            source.close()
            source.threadsTerminated.assert().isTrue()
            scheduler.isShutdown.assert().isFalse()
        } finally {
            scheduler.shutdownNow()
        }
    }

    private companion object {
        const val CLOSE_SIGNAL_MILLIS = 300L
        const val SHORT_TIMEOUT_MILLIS = 50L
    }
}
