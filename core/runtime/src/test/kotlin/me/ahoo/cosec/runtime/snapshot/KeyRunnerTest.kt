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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.captured
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [KeyRunner] behind the source's factory (RFC 0013 B2b, step 2b): what the page promises of the runner itself. §5's `VirtualMachineError` from an effect, on a caller's thread
 * and on the compile pool; `close()` called from inside a key's own drain.
 */
class KeyRunnerTest {
    private val store = ControllableStore()
    private val compiler = FakeCompiler()
    private val lines = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(SnapshotSource::class.java.name) as Logger

    @BeforeEach
    fun capture() {
        lines.start()
        logger.addAppender(lines)
        store.put(SnapshotKey.Platform, document(1))
        store.put(APP_A, document(1))
        store.put(APP_B, document(1))
    }

    @AfterEach
    fun release() {
        logger.detachAppender(lines)
    }

    private fun runners(metrics: SnapshotMetrics = SnapshotMetrics.NOOP, compileExecutor: Executor? = directExecutor) = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, OPTIONS.maxApps, OPTIONS.maxAbsentApps)),
        OPTIONS,
        ManualClock(),
        metrics,
        compileExecutor,
        null,
        { 0L },
        null,
        keyRunners(),
    )

    /** A meter that throws [error] once, on the first install of [key]. */
    private fun throwingOnce(key: SnapshotKey, error: () -> VirtualMachineError) = object : SnapshotMetrics {
        private val armed = AtomicBoolean(true)

        override fun onInstall(entry: SnapshotEntry<*>) {
            if (entry.key == key && armed.getAndSet(false)) throw error()
        }
    }

    @Test
    fun `an effect's VirtualMachineError on a caller's thread is logged, the call returns, and the key goes on`() {
        runners(throwingOnce(APP_A) { StackOverflowError("meter") }).use { source ->
            // The caller's thread drains: the read answers at once and the compile pool runs in place.
            val snapshot = source.load("app-a").join()
            snapshot.appCompiled.revision.assert().isEqualTo(1L)
            awaitCondition {
                lines.captured().any { "StackOverflowError" in it.formattedMessage && "may not be healthy" in it.formattedMessage }
            }
            lines.captured().single { "may not be healthy" in it.formattedMessage }.formattedMessage.assert().contains(
                "[app:app-a]",
                "Install"
            )
            store.put(APP_A, document(2))
            source.reload(APP_A).join().revision.assert().isEqualTo(2L)
        }
    }

    @Test
    fun `an effect's VirtualMachineError on the compile pool is rethrown there, after its waiters are answered`() {
        val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> uncaught += thread.name to error }
        try {
            runners(throwingOnce(APP_A) { StackOverflowError("meter") }, compileExecutor = null).use { source ->
                source.load().join()
                // The compile waits until the request's drain has returned, so the pool takes its own Compiled.
                val gate = CountDownLatch(1).also { compiler.gate = it }
                val app = source.load("app-a")
                gate.countDown()
                app.join().appCompiled.revision.assert().isEqualTo(1L)
                awaitCondition { uncaught.isNotEmpty() }
                val (thread, error) = uncaught.single()
                thread.assert().startsWith("cosec-snapshot-compile")
                error.assert().isInstanceOf(StackOverflowError::class.java)
                lines.captured().none { "may not be healthy" in it.formattedMessage }.assert().isTrue()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `close from inside a key's own drain completes every other key at its return, and that key when its drain returns`() {
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val reloadingB = CompletableFuture<CompletableFuture<SnapshotEntry<*>>>()
        val atClose = CopyOnWriteArrayList<Boolean>()
        val metrics = object : SnapshotMetrics {
            override fun onInstall(entry: SnapshotEntry<*>) {
                if (entry.key == APP_A && entry.revision == 2L) {
                    // Returns without waiting on the flag this thread holds; another key is complete when it does.
                    source.close()
                    atClose += reloadingB.join().isDone
                }
            }
        }
        source = runners(metrics)
        source.loadAll(listOf(APP_A, APP_B)).join()
        store.hold = true
        reloadingB.complete(source.reload(APP_B))
        store.hold = false
        store.put(APP_A, document(2))
        // This key's drain took the Close it queued: its future is complete once that drain, this call's, has returned.
        val reloadingA = source.reload(APP_A)
        atClose.assert().containsExactly(true)
        reloadingA.isDone.assert().isTrue()
    }

    @Test
    fun `a request armed after close, when close comes between load's check and the request, is refused at once`() {
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val closing = AtomicBoolean()
        // `load` checks `closed`, then requests the platform (a hit, counted here) before the app: close in between.
        val metrics = object : SnapshotMetrics {
            override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
                if (kind == SnapshotKind.PLATFORM && closing.getAndSet(false)) source.close()
            }
        }
        source = runners(metrics)
        source.load().join()
        closing.set(true)
        val app = source.load("app-a")
        app.isCompletedExceptionally.assert().isTrue()
        assertThrows<CompletionException> { app.join() }.cause!!.message.assert().isEqualTo("SnapshotSource is closed.")
        // Refused in `arm`, before any event is offered: nothing read or compiled the app.
        compiler.compiled.none { it.first == APP_A }.assert().isTrue()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 10 seconds" }
            Thread.sleep(10)
        }
    }

    private companion object {
        val APP_A = SnapshotKey.App("app-a")
        val APP_B = SnapshotKey.App("app-b")
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
    }
}
