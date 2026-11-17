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
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * How [KeyRunner] fails (RFC 0013 B2b §3, §5): a crash on a pool thread still lets a lost offer be taken; a
 * `VirtualMachineError` from a transition, on a caller's thread and on the compile pool; an eviction whose transition
 * throws gives its claim back; a hand-off the pools refuse is counted, and run.
 */
class KeyRunnerFailureTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val compiler = FakeCompiler()
    private val lines = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(SnapshotSource::class.java.name) as Logger
    private val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    @BeforeEach
    fun capture() {
        lines.start()
        logger.addAppender(lines)
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> uncaught += thread.name to error }
    }

    @AfterEach
    fun release() {
        logger.detachAppender(lines)
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    private fun source(
        hooks: KeyDrain.Hooks = KeyDrain.Hooks.NONE,
        metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
        compileExecutor: Executor? = directExecutor,
        housekeeping: ExecutorService? = null,
        options: SnapshotOptions = OPTIONS,
        k: Int = KeyRunner.PROVISIONAL_K,
        everyKey: Boolean = false,
    ): SnapshotSource<Compiled, Compiled> {
        val ticker = AtomicLong()
        return SnapshotSource(
            store,
            SnapshotKey.Platform,
            compiler,
            listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsent)),
            options,
            ManualClock(),
            metrics,
            compileExecutor,
            null,
            { ticker.incrementAndGet() },
            housekeeping,
            object : SlotFactory {
                override fun <T : Any> create(
                    key: SnapshotKey,
                    compiler: SnapshotCompiler<T>,
                    context: SlotContext
                ): KeySlot<T> =
                    KeyRunner(key, compiler, context, k, if (everyKey || key == APP_A) hooks else KeyDrain.Hooks.NONE)
            },
        )
    }

    private fun SnapshotSource<*, *>.slot(id: String): KeySlot<*> = families.first {
        it.spec.kind == SnapshotKind.APP
    }.slots.getValue(
        id
    )

    @Test
    fun `a crash on the compile pool still lets an offer that lost the flag be taken before the error leaves the drain`() {
        val crashed = AtomicBoolean()
        val late = CompletableFuture<CompletableFuture<Boolean>>()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val metrics = object : SnapshotMetrics {
            override fun onInstall(entry: SnapshotEntry<*>) {
                if (entry.key == APP_A && crashed.compareAndSet(false, true)) throw StackOverflowError("meter")
            }
        }
        // Once the crash is in, an offer made on this thread while it holds the flag: it must be taken all the same.
        val hooks = KeyDrain.Hooks(
            afterEmpty = { if (crashed.get() && !late.isDone) late.complete(source.slot("app-a").markRecompile()) }
        )
        source = source(hooks, metrics, compileExecutor = null)
        source.use {
            source.load("app-a").join()
            awaitCondition { uncaught.isNotEmpty() }
            late.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS).assert().isTrue()
            uncaught.single().first.assert().startsWith("cosec-snapshot-compile")
        }
    }

    @Test
    fun `a VirtualMachineError from a transition on a caller's thread is logged, and its carrier answered`() {
        val armed = AtomicBoolean(true)
        source(
            KeyDrain.Hooks({ if (it is KeyEvent.Requested && armed.getAndSet(false)) throw StackOverflowError("next") })
        ).use { source ->
            source.load().join()
            (source.load("app-a").join().app is DocumentState.Failed).assert().isTrue()
            awaitCondition { lines.list.any { "the transition of Requested" in it.formattedMessage } }
            uncaught.assert().isEmpty()
        }
    }

    @Test
    fun `a VirtualMachineError from a transition on the compile pool is rethrown there, after its waiters are answered`() {
        val armed = AtomicBoolean(true)
        source(
            KeyDrain.Hooks({ if (it is KeyEvent.Compiled && armed.getAndSet(false)) throw StackOverflowError("next") }),
            compileExecutor = null
        ).use { source ->
            source.load().join()
            (source.load("app-a").join().app is DocumentState.Failed).assert().isTrue()
            awaitCondition { uncaught.isNotEmpty() }
            uncaught.single().first.assert().startsWith("cosec-snapshot-compile")
        }
    }

    @Test
    fun `an eviction whose transition throws gives its claim back, and a later pass evicts the key`() {
        for (absent in listOf(false, true)) {
            val ids = (1..3).map { if (absent) "made-up-$it" else "app-$it" }
            if (!absent) ids.forEach { store.put(SnapshotKey.App(it), document(1)) }
            val armed = AtomicBoolean()
            val held = SlotModelThreads().housekeeping
            val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = 2, maxAbsent = 2)
            source(
                KeyDrain.Hooks({ if (it is KeyEvent.Evict && armed.getAndSet(false)) error("thrown by the test") }),
                housekeeping = held,
                options = options,
                everyKey = true
            ).use { source ->
                source.load().join()
                ids.forEach { source.load(it).join() }
                val counts = source.heldCounts(SnapshotKind.APP)
                armed.set(true)
                source.slot(
                    ids.first()
                ).evict(onlyIfEmpty = false, claimedAbsent = absent).join().assert().isEqualTo(Eviction.REFUSED)
                source.heldCounts(SnapshotKind.APP).assert().describedAs("absent=$absent").isEqualTo(counts)
                if (absent) source.heldAbsentMembers(SnapshotKind.APP).assert().contains(SnapshotKey.App(ids.first()))
                source.evictionPass(SnapshotKind.APP, Int.MAX_VALUE)
                source.holds(SnapshotKey.App(ids.first())).assert().describedAs("absent=$absent").isFalse()
            }
        }
    }

    @Test
    fun `a hand-off both pools refuse is counted, and the caller runs the source's hand-offs`() {
        val ranInPlace = AtomicInteger()
        val housekept = AtomicInteger()
        val metrics = object : SnapshotMetrics {
            override fun onHandOffRefused(ranInPlace: Boolean) {
                if (ranInPlace) ranInPlaceCount() else housekept.incrementAndGet()
            }

            fun ranInPlaceCount() = ranInPlace.incrementAndGet()
        }
        val refuse = AtomicBoolean()
        // Accepting until A is installed, so A is held; refusing after, so the hand-off is refused.
        val refusing = Executor {
            if (refuse.get()) {
                throw RejectedExecutionException(
                    "refused by the test"
                )
            } else {
                it.run()
            }
        }
        val more = CopyOnWriteArrayList<CompletableFuture<Boolean>>()
        val armed = AtomicBoolean()
        lateinit var source: SnapshotSource<Compiled, Compiled>
        val hooks = KeyDrain.Hooks(
            {
                if (it is KeyEvent.Recompile && armed.getAndSet(false)) {
                    repeat(
                        EXTRA
                    ) { more += source.slot("app-a").markRecompile() }
                }
            }
        )
        source = source(hooks, metrics, compileExecutor = refusing, housekeeping = Refusing(), k = 0)
        source.use {
            source.load("app-a").join()
            refuse.set(true)
            armed.set(true)
            more += source.slot("app-a").markRecompile()
            more.all { it.isDone }.assert().isTrue()
            ranInPlace.get().assert().isGreaterThan(0)
            housekept.get().assert().isEqualTo(0)
        }
    }

    /** Housekeeping that refuses everything, so a hand-off the compile pool refused runs in place. */
    private class Refusing : AbstractExecutorService() {
        override fun execute(command: Runnable) = throw RejectedExecutionException("refused by the test")

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 10 seconds" }
            Thread.sleep(10)
        }
    }

    private companion object {
        val APP_A = SnapshotKey.App("app-a")
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
        const val EXTRA = 5
    }
}
