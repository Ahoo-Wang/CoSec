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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cold path (RFC 0003 §4.3): timeouts (F7), a full compile queue (F8), cancellation (F14), and the threads it runs
 * on. `Schedulers.newParallel` threads implement `NonBlocking`, like the Netty event loop, so they stand in for it.
 */
class SnapshotColdLoadTest {
    companion object {
        private const val BURST = 50
        private const val COLD_TIMEOUT_MILLIS = 600L
        private const val REFUSED_WAIT_MILLIS = 200L

        /** The timers' and the scheduler's own granularity: a deadline is never met early by more than this. */
        private const val DEADLINE_SLACK_MILLIS = 50L

        /** What a loaded CI runner may add after the deadline: never seconds. */
        private const val LATE_MILLIS = 1_500L

        private val eventLoop = Schedulers.newParallel("test-event-loop", 1)

        @AfterAll
        @JvmStatic
        fun dispose() {
            eventLoop.dispose()
        }
    }

    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1, "a1"))
        put(APP_B, document(1, "b1"))
        put(APP_C, document(1, "c1"))
    }

    private fun <T : Any> onEventLoop(block: () -> T): T = Mono.fromCallable {
        Schedulers.isNonBlockingThread(Thread.currentThread()).assert().isTrue()
        block()
    }.subscribeOn(eventLoop).block(Duration.ofSeconds(10))!!

    @Test
    fun hotPathRunsOnTheCallerThreadAndTouchesNoExecutorOrStore() {
        val metricThreads = CopyOnWriteArrayList<Thread>()
        val metrics = object : SnapshotMetrics {
            override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
                if (outcome == LoadOutcome.HIT) metricThreads.add(Thread.currentThread())
            }
        }
        val compilePool = Executors.newFixedThreadPool(1)
        val compileTasks = AtomicInteger()
        val countingCompile = Executor {
            compileTasks.incrementAndGet()
            compilePool.execute(it)
        }
        val scheduledTasks = AtomicInteger()
        val scheduler = object : ScheduledThreadPoolExecutor(1) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                scheduledTasks.incrementAndGet()
                return super.schedule(command, delay, unit)
            }
        }
        testSource(store, metrics = metrics, compileExecutor = countingCompile, scheduler = scheduler).use { source ->
            source.load("app-a").get(10, TimeUnit.SECONDS)
            val reads = store.reads.get()
            val conditionalReads = store.conditionalReads.get()
            val compiles = compileTasks.get()
            val scheduled = scheduledTasks.get()

            val (caller, snapshot) = onEventLoop { Thread.currentThread() to source.peek("app-a")!! }

            snapshot.appCompiled.entries.assert().containsExactly("a1")
            metricThreads.assert().hasSize(2).allMatch { it === caller }
            compileTasks.get().assert().isEqualTo(compiles)
            scheduledTasks.get().assert().isEqualTo(scheduled)
            store.reads.get().assert().isEqualTo(reads)
            store.conditionalReads.get().assert().isEqualTo(conditionalReads)
        }
        compilePool.shutdown()
        scheduler.shutdown()
    }

    @Test
    fun coldMissCompilesOffTheCallerThread() {
        val compiler = FakeCompiler()
        val inMemory = InMemorySnapshotDocumentStore().apply {
            put(SnapshotKey.Platform, document(1))
            put(APP_A, document(1, "a1"))
        }
        testSource(inMemory, compiler = compiler, compileExecutor = null).use { source ->
            val (caller, future) = onEventLoop { Thread.currentThread() to source.load("app-a") }
            future.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("a1")

            compiler.threads.assert().hasSize(2)
            compiler.threads.forEach { thread ->
                thread.assert().isNotSameAs(caller)
                Schedulers.isNonBlockingThread(thread).assert().isFalse()
                thread.name.assert().startsWith("cosec-snapshot-compile")
            }
        }
    }

    @Test
    fun f7ColdLoadTimeoutFailsTheRequestWhileTheLoadGoesOn() {
        val metrics = RecordingMetrics()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, coldLoadTimeout = Duration.ofMillis(50))
        testSource(store, options = options, metrics = metrics).use { source ->
            store.hold = true

            val snapshot = source.load().get(10, TimeUnit.SECONDS)

            val failure = (snapshot.policies as DocumentState.Failed).error
            failure.assert().isInstanceOf(SnapshotUnavailableException::class.java)
            failure.cause.assert().isInstanceOf(TimeoutException::class.java)
            metrics.loads.assert().containsExactly(SnapshotKind.PLATFORM to LoadOutcome.TIMEOUT)
            source.peek().assert().isNull()

            store.releaseAll()
            metrics.loads.assert().hasSize(1)
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)
        }
    }

    /**
     * R2D-4: a cold load the full compile queue refuses waits and compiles again once the queue drains, within the
     * cold load timeout; the installed documents are untouched meanwhile.
     */
    @Test
    fun f8AColdLoadTheFullCompileQueueRefusesWaitsAndCompilesOnceItDrains() {
        val compiler = FakeCompiler()
        val options = SnapshotOptions(
            revalidateInterval = QUIET_PASS,
            compileThreads = 1,
            compileQueueCapacity = 1,
            coldCompileRetry = Duration.ofMillis(20),
        )
        testSource(store, compiler = compiler, options = options, compileExecutor = null).use { source ->
            source.load().get(10, TimeUnit.SECONDS)
            val gate = CountDownLatch(1)
            compiler.gate = gate

            val running = source.load("app-a")
            await { compiler.threads.size == 2 }
            val queued = source.load("app-b")
            val refused = source.load("app-c")

            Thread.sleep(REFUSED_WAIT_MILLIS)
            refused.isDone.assert().isFalse()
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)
            source.peek("app-c").assert().isNull()

            gate.countDown()
            running.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("a1")
            queued.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("b1")
            refused.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("c1")
        }
    }

    /** R2D-4: a cold load the queue keeps refusing fails closed at its deadline, never later, and installs nothing. */
    @Test
    fun f8AColdLoadTheQueueRefusesUntilItsDeadlineFailsAtIt() {
        val compiler = FakeCompiler()
        val timeout = Duration.ofMillis(COLD_TIMEOUT_MILLIS)
        val options = SnapshotOptions(
            revalidateInterval = QUIET_PASS,
            compileThreads = 1,
            compileQueueCapacity = 1,
            coldCompileRetry = Duration.ofMillis(20),
            coldLoadTimeout = timeout,
        )
        testSource(store, compiler = compiler, options = options, compileExecutor = null).use { source ->
            source.load().get(10, TimeUnit.SECONDS)
            val gate = CountDownLatch(1)
            compiler.gate = gate
            source.load("app-a")
            await { compiler.threads.size == 2 }
            source.load("app-b")

            val started = System.nanoTime()
            val refused = source.load("app-c").get(10, TimeUnit.SECONDS)
            val waited = Duration.ofNanos(System.nanoTime() - started)

            waited.assert().isGreaterThanOrEqualTo(timeout.minusMillis(DEADLINE_SLACK_MILLIS))
            waited.assert().isLessThan(timeout.plusMillis(LATE_MILLIS))
            refused.app.assert().isInstanceOf(DocumentState.Failed::class.java)
            source.peek("app-c").assert().isNull()
            gate.countDown()
        }
    }

    /**
     * R2D-4, a new replica's first moment: many distinct cold documents at once against a compile pool of one thread and
     * a queue of two. None is refused while the cold load timeout holds; each compiles once the pool frees up.
     */
    @Test
    fun aBurstOfDistinctColdDocumentsQueuesWithinTheDeadline() {
        val compiler = FakeCompiler()
        val apps = (1..BURST).map { "burst-$it" }
        apps.forEach { store.put(SnapshotKey.App(it), document(1, it)) }
        val options = SnapshotOptions(
            revalidateInterval = QUIET_PASS,
            compileThreads = 1,
            compileQueueCapacity = 2,
            coldCompileRetry = Duration.ofMillis(20),
            coldLoadTimeout = Duration.ofSeconds(5),
        )
        testSource(store, compiler = compiler, options = options, compileExecutor = null).use { source ->
            source.load().get(10, TimeUnit.SECONDS)
            val gate = CountDownLatch(1)
            compiler.gate = gate
            val loads = apps.map { source.load(it) }

            Thread.sleep(REFUSED_WAIT_MILLIS)
            loads.none { it.isDone }.assert().isTrue()

            gate.countDown()
            loads.zip(apps).forEach { (load, app) ->
                load.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly(app)
            }
        }
    }

    /** R2D-4: the same burst against a pool that never frees up: every load is refused at its deadline, none before. */
    @Test
    fun aBurstOfDistinctColdDocumentsIsRefusedAtTheDeadline() {
        val compiler = FakeCompiler()
        val apps = (1..BURST).map { "burst-$it" }
        apps.forEach { store.put(SnapshotKey.App(it), document(1, it)) }
        val timeout = Duration.ofMillis(COLD_TIMEOUT_MILLIS)
        val options = SnapshotOptions(
            revalidateInterval = QUIET_PASS,
            compileThreads = 1,
            compileQueueCapacity = 2,
            coldCompileRetry = Duration.ofMillis(20),
            coldLoadTimeout = timeout,
        )
        testSource(store, compiler = compiler, options = options, compileExecutor = null).use { source ->
            source.load().get(10, TimeUnit.SECONDS)
            val gate = CountDownLatch(1)
            compiler.gate = gate
            val started = System.nanoTime()
            val loads = apps.map { source.load(it) }

            Thread.sleep(timeout.minusMillis(DEADLINE_SLACK_MILLIS).toMillis())
            loads.none { it.isDone }.assert().isTrue()

            loads.forEach { it.get(10, TimeUnit.SECONDS).app.assert().isInstanceOf(DocumentState.Failed::class.java) }
            Duration.ofNanos(System.nanoTime() - started).assert().isLessThan(timeout.plusMillis(LATE_MILLIS))
            apps.forEach { source.peek(it).assert().isNull() }
            gate.countDown()
        }
    }

    @Test
    fun f8FullCompileQueueKeepsTheInstalledRevisionOnReload() {
        val compiler = FakeCompiler()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, compileThreads = 1, compileQueueCapacity = 1)
        testSource(store, compiler = compiler, options = options, compileExecutor = null).use { source ->
            source.load("app-a").get(10, TimeUnit.SECONDS)
            val gate = CountDownLatch(1)
            compiler.gate = gate
            source.load("app-b")
            await { compiler.threads.size == 3 }
            source.load("app-c")

            store.put(SnapshotKey.Platform, document(2))
            source.reload(SnapshotKey.Platform).get(10, TimeUnit.SECONDS).revision.assert().isEqualTo(1)
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)

            // The rejected reload is retried on its own once the queue drains: no further notification is needed.
            gate.countDown()
            await { (source.peek()!!.policies as? DocumentState.Present)?.compiled?.revision == 2L }
        }
    }

    @Test
    fun f14CancelledRequestLeavesTheSharedLoadRunning() {
        testSource(store).use { source ->
            source.load().join()
            store.hold = true
            val cancelled = source.load("app-a")
            val other = source.load("app-a")

            cancelled.cancel(true)
            store.releaseAll()

            other.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("a1")
            source.peek("app-a")!!.appCompiled.entries.assert().containsExactly("a1")
            store.reads.get().assert().isEqualTo(2)
        }
    }
}
