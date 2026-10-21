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
        private val eventLoop = Schedulers.newParallel("test-event-loop", 1)

        @AfterAll
        @JvmStatic
        fun dispose() {
            eventLoop.dispose()
        }
    }

    private val store = ControllableStore().apply {
        put(SnapshotKey.Policies, document(1))
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
            put(SnapshotKey.Policies, document(1))
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
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, coldLoadTimeout = Duration.ofMillis(50))
        testSource(store, options = options, metrics = metrics).use { source ->
            store.hold = true

            val snapshot = source.load().get(10, TimeUnit.SECONDS)

            val failure = (snapshot.policies as DocumentState.Failed).error
            failure.assert().isInstanceOf(SnapshotUnavailableException::class.java)
            failure.cause.assert().isInstanceOf(TimeoutException::class.java)
            metrics.loads.assert().containsExactly(SnapshotKind.POLICIES to LoadOutcome.TIMEOUT)
            source.peek().assert().isNull()

            store.releaseAll()
            metrics.loads.assert().hasSize(1)
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)
        }
    }

    @Test
    fun f8FullCompileQueueFailsColdLoadsAndLeavesInstalledDocuments() {
        val compiler = FakeCompiler()
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, compileThreads = 1, compileQueueCapacity = 1)
        testSource(store, compiler = compiler, options = options, compileExecutor = null).use { source ->
            source.load().get(10, TimeUnit.SECONDS)
            val gate = CountDownLatch(1)
            compiler.gate = gate

            val running = source.load("app-a")
            await { compiler.threads.size == 2 }
            val queued = source.load("app-b")
            val rejected = source.load("app-c").get(10, TimeUnit.SECONDS)

            val failure = (rejected.app as DocumentState.Failed).error
            failure.assert().isInstanceOf(SnapshotUnavailableException::class.java)
            failure.message.assert().contains("compile queue is full")
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)
            source.peek("app-c").assert().isNull()

            gate.countDown()
            running.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("a1")
            queued.get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("b1")
            source.load("app-c").get(10, TimeUnit.SECONDS).appCompiled.entries.assert().containsExactly("c1")
        }
    }

    @Test
    fun f8FullCompileQueueKeepsTheInstalledRevisionOnReload() {
        val compiler = FakeCompiler()
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, compileThreads = 1, compileQueueCapacity = 1)
        testSource(store, compiler = compiler, options = options, compileExecutor = null).use { source ->
            source.load("app-a").get(10, TimeUnit.SECONDS)
            val gate = CountDownLatch(1)
            compiler.gate = gate
            source.load("app-b")
            await { compiler.threads.size == 3 }
            source.load("app-c")

            store.put(SnapshotKey.Policies, document(2))
            source.reload(SnapshotKey.Policies).get(10, TimeUnit.SECONDS).revision.assert().isEqualTo(1)
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
