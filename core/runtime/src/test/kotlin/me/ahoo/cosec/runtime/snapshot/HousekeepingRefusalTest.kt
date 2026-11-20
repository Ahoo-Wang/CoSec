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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What housekeeping refuses never lands on the thread that offered it (`SNP-19`, #1534). Housekeeping here is the real
 * one ([SnapshotExecutors.newHousekeeping]: one thread, [SnapshotExecutors.HOUSEKEEPING_QUEUE] queued), held full by a
 * task that waits on [full] behind a full queue. A refused log line, eviction pass or lag report is dropped and counted;
 * an empty key's drop runs in place, counted too; and once housekeeping runs again, it writes one summary of what it
 * dropped. No line is written on the caller's thread meanwhile.
 */
class HousekeepingRefusalTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
        put(APP_B, document(1))
        put(APP_C, document(1))
    }
    private val compiler = FakeCompiler()
    private val housekeeping = SnapshotExecutors.newHousekeeping()
    private val full = CountDownLatch(1)
    private val lines = Lines()
    private val logger = LoggerFactory.getLogger(SnapshotSource::class.java.name) as Logger
    private val refused = CopyOnWriteArrayList<HousekeepingTask>()
    private val recording = object : SnapshotMetrics {
        override fun onHousekeepingRefused(task: HousekeepingTask) {
            refused += task
        }
    }

    /**
     * Keeps every line of the source's logger with its thread, and holds a line that `SNP-19` keeps off a caller's
     * thread ([covered]) written on [slow]'s thread until the case ends: a log appender that stalls, as in the issue.
     */
    private class Lines : AppenderBase<ILoggingEvent>() {
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val released = CountDownLatch(1)

        @Volatile
        var slow: Thread? = null

        override fun append(event: ILoggingEvent) {
            events += event
            if (Thread.currentThread() == slow && covered(event)) released.await(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @BeforeEach
    fun capture() {
        lines.start()
        logger.addAppender(lines)
    }

    @AfterEach
    fun release() {
        lines.released.countDown()
        full.countDown()
        logger.detachAppender(lines)
        housekeeping.shutdownNow()
    }

    private fun source(
        metrics: SnapshotMetrics = recording,
        options: SnapshotOptions = OPTIONS,
        compileExecutor: Executor? = directExecutor,
        hooks: KeyDrain.Hooks = KeyDrain.Hooks.NONE,
    ): SnapshotSource<Compiled, Compiled> {
        val ticker = AtomicLong()
        return SnapshotSource(
            store,
            SnapshotKey.Platform,
            compiler,
            listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsentApps)),
            options,
            ManualClock(),
            metrics,
            compileExecutor,
            null,
            { ticker.incrementAndGet() },
            housekeeping,
            keyRunners(hooks = hooks),
        ).also {
            it.load().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            // The shared document's first load reads a local copy, and a load confirming it follows in the same
            // step: its read is issued before `load` is answered. A case starts once that load has ended too, or a
            // pass's load would join it and confirm what it read, from before the case changed the store.
            awaitCondition { it.quiescent() }
        }
    }

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

    /** Housekeeping runs again, and has run all it was given. */
    private fun empty() {
        full.countDown()
        awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
    }

    /** Lines that `SNP-19` covers written on [thread]: none may be on a caller's thread. */
    private fun writtenOn(thread: Thread): List<String> =
        lines.events.filter { it.threadName == thread.name && covered(it) }.map { it.formattedMessage }

    /**
     * Ends the case: housekeeping runs again before the source closes, which waits for it. A DEBUG line without a stack
     * (an evicted key's) is not covered.
     */
    private inline fun <R> SnapshotSource<Compiled, Compiled>.closing(
        block: (SnapshotSource<Compiled, Compiled>) -> R
    ): R =
        try {
            block(this)
        } finally {
            full.countDown()
            close()
        }

    private fun summaries(): List<ILoggingEvent> =
        lines.events.filter { it.formattedMessage.startsWith("Snapshot housekeeping since its last summary") }

    /** The summary once housekeeping runs again: one tick queues the lag report, which writes it. */
    private fun SnapshotSource<*, *>.summaryAfterRelease(): String {
        empty()
        tick().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
        awaitCondition { summaries().isNotEmpty() && housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }
        val summary = summaries().single()
        summary.level.assert().isEqualTo(Level.WARN)
        summary.threadName.assert().startsWith(HOUSEKEEPING)
        return summary.formattedMessage
    }

    private fun SnapshotSource<*, *>.apps(): Int = entries().count { it.key.kind == SnapshotKind.APP }

    private fun SnapshotSource<*, *>.holdsSlot(id: String): Boolean =
        families.first { it.spec.kind == SnapshotKind.APP }.slots.containsKey(id)

    /**
     * The issue: a store that fails while housekeeping is full and the log appender stalls. The request is answered on
     * its own thread without waiting for the appender: its load's line is dropped and counted, its empty key dropped in
     * place. With no metrics at all, the summary still says so.
     */
    @Test
    fun `a failed load's line is dropped, not written on the caller's thread, and the summary names it`() {
        source(SnapshotMetrics.NOOP).closing { source ->
            fill()
            store.failure = IOException("connection reset")
            val answered = CompletableFuture<Snapshot<Compiled, Compiled>>()
            val caller = Thread {
                source.load("app-x").whenComplete { snapshot, error ->
                    if (error != null) answered.completeExceptionally(error) else answered.complete(snapshot)
                }
            }
            lines.slow = caller
            caller.start()

            val snapshot = answered.get(WITHIN_SECONDS, TimeUnit.SECONDS)

            (snapshot.app is DocumentState.Failed).assert().isTrue()
            caller.join(TimeUnit.SECONDS.toMillis(WITHIN_SECONDS))
            writtenOn(caller).assert().isEmpty()
            // The drop ran in place: the failed key holds nothing, and its slot is gone.
            source.holdsSlot("app-x").assert().isFalse()
            summaries().assert().isEmpty()

            val summary = source.summaryAfterRelease()

            summary.assert().contains("refused 2 tasks, full or stopped")
            summary.assert().contains("load-log 1 (last: \"")
            summary.assert().contains("— java.io.IOException: connection reset\")")
            summary.assert().contains("drop 1 (last: \"The drop of empty key [app:app-x].\")")
        }
    }

    @Test
    fun `a compile failure's line is dropped and counted`() {
        source().closing { source ->
            fill()
            compiler.failure = IllegalStateException("no such matcher")

            (source.load("app-a").get(WITHIN_SECONDS, TimeUnit.SECONDS).app is DocumentState.Present).assert().isFalse()

            writtenOn(Thread.currentThread()).assert().isEmpty()
            refused.assert().contains(HousekeepingTask.COMPILE_LOG)
            source.summaryAfterRelease().assert().contains("compile-log 1 (last: \"")
        }
    }

    /**
     * A `VirtualMachineError` from an effect on a caller's thread (`SNP-16`) is logged there no more: its report is
     * refused, and so is the line of the failed effect that follows it, once the load has ended.
     */
    @Test
    fun `a crash's report and its failed effect's line, on a caller's thread, are dropped and counted`() {
        val crashed = AtomicBoolean()
        val metrics = object : SnapshotMetrics {
            override fun onInstall(entry: SnapshotEntry<*>) {
                if (entry.key == APP_A && crashed.compareAndSet(false, true)) throw StackOverflowError("meter")
            }

            override fun onHousekeepingRefused(task: HousekeepingTask) {
                refused += task
            }
        }
        source(metrics).closing { source ->
            fill()

            source.load("app-a").get(WITHIN_SECONDS, TimeUnit.SECONDS)

            awaitCondition { HousekeepingTask.CRASH_LOG in refused && HousekeepingTask.EFFECT_LOG in refused }
            writtenOn(Thread.currentThread()).assert().isEmpty()
        }
    }

    @Test
    fun `an aborted transition's line is dropped and counted`() {
        val armed = AtomicBoolean()
        val hooks = KeyDrain.Hooks(
            { if (it is KeyEvent.Requested && armed.getAndSet(false)) throw StackOverflowError("next") }
        )
        source(hooks = hooks).closing { source ->
            fill()
            armed.set(true)

            (source.load("app-a").get(WITHIN_SECONDS, TimeUnit.SECONDS).app is DocumentState.Failed).assert().isTrue()

            awaitCondition { HousekeepingTask.TRANSITION_LOG in refused }
            writtenOn(Thread.currentThread()).assert().isEmpty()
        }
    }

    @Test
    fun `a prewarm warning is dropped and counted`() {
        source().closing { source ->
            fill()
            store.failure = IOException("connection reset")

            source.prewarm(listOf(APP_A))

            awaitCondition { HousekeepingTask.PREWARM_LOG in refused }
            writtenOn(Thread.currentThread()).assert().isEmpty()
        }
    }

    @Test
    fun `a reset's line is dropped and counted`() {
        source().closing { source ->
            fill()

            store.reset()

            refused.assert().contains(HousekeepingTask.RESET_LOG)
            writtenOn(Thread.currentThread()).assert().isEmpty()
            source.summaryAfterRelease().assert().contains("reset-log 1")
        }
    }

    /** The warning about a stalled pass is the timer's: refused, it is dropped as the timer's always was, and counted. */
    @Test
    fun `a stalled pass's warning is dropped and counted`() {
        val nanos = ManualNanoTime()
        source(options = SnapshotOptions(revalidateInterval = QUIET_PASS), compileExecutor = null).closing { source ->
            source.nanoTime = nanos
            store.dropNotifications = true
            store.put(SnapshotKey.Platform, document(2))
            val gate = CountDownLatch(1)
            compiler.gate = gate
            try {
                source.tick()
                awaitCondition { compiler.threads.size == 2 }
                fill()
                nanos.advance(SnapshotOptions().storeReadTimeout.plusSeconds(1))

                source.tick()

                refused.assert().contains(HousekeepingTask.PASS_LOG)
                writtenOn(Thread.currentThread()).assert().isEmpty()
            } finally {
                gate.countDown()
            }
        }
    }

    /**
     * Housekeeping stays full across several ticks: every load's end asks for the pass, refused each time, and the lag
     * report with it. The family stays at or under its cap throughout, and nothing is summarized, since housekeeping
     * cannot run. Once it runs again, the next tick's load ends bring the family to its bound, and one summary names
     * what was dropped.
     */
    @Test
    fun `housekeeping full across ticks keeps the family under its cap, and the late pass takes it to its bound`() {
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = 2)
        val cap = options.maxApps * 2
        source(options = options).closing { source ->
            source.load("app-a").get(WITHIN_SECONDS, TimeUnit.SECONDS)
            source.load("app-b").get(WITHIN_SECONDS, TimeUnit.SECONDS)
            fill()

            source.load("app-c").get(WITHIN_SECONDS, TimeUnit.SECONDS)
            repeat(TICKS) {
                source.tick().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
                source.apps().assert().isLessThanOrEqualTo(cap)
            }

            source.apps().assert().isEqualTo(3)
            refused.assert().contains(HousekeepingTask.EVICT)
            refused.assert().contains(HousekeepingTask.LAG_REPORT)
            summaries().assert().isEmpty()
            writtenOn(Thread.currentThread()).assert().isEmpty()

            val summary = source.summaryAfterRelease()

            awaitCondition { source.apps() == options.maxApps }
            summary.assert().contains("evict ")
            summary.assert().contains("lag-report $TICKS (last: \"The snapshot lag report.\")")
        }
    }

    /** A meter that throws at every refusal is counted in the summary's own state; the caller never sees it. */
    @Test
    fun `a throwing refusal meter never reaches the caller, and the summary counts it`() {
        val metrics = object : SnapshotMetrics {
            override fun onHousekeepingRefused(task: HousekeepingTask) = throw IllegalStateException("meter down")
        }
        source(metrics).closing { source ->
            fill()
            store.failure = IOException("connection reset")

            (source.load("app-x").get(WITHIN_SECONDS, TimeUnit.SECONDS).app is DocumentState.Failed).assert().isTrue()

            writtenOn(Thread.currentThread()).assert().isEmpty()
            source.summaryAfterRelease().assert().contains(
                "meter failures 2 (last: \"SnapshotMetrics.onHousekeepingRefused — java.lang.IllegalStateException: meter down\")"
            )
        }
    }

    /**
     * Housekeeping, which would write the summary, is gone once the source closes: the closing thread writes what was
     * refused and not yet summarized, once, whatever the interval, after it waited for the threads.
     */
    @Test
    fun `close while housekeeping is full writes what it refused, once, on the closing thread`() {
        val source = source(SnapshotMetrics.NOOP)
        fill()
        store.failure = IOException("connection reset")
        (source.load("app-x").get(WITHIN_SECONDS, TimeUnit.SECONDS).app is DocumentState.Failed).assert().isTrue()
        source.closeTimeout = Duration.ofMillis(CLOSE_TIMEOUT_MILLIS)

        source.close()

        val summary = summaries().single()
        summary.threadName.assert().isEqualTo(Thread.currentThread().name)
        summary.level.assert().isEqualTo(Level.WARN)
        summary.formattedMessage.assert().contains("load-log 1 (last: \"")
    }

    /**
     * Housekeeping stopped is refusal too: a caller's line after close is dropped and counted. On one of the source's
     * own threads the task runs in place, uncounted, as before: that thread is no caller's, and closing loses nothing.
     */
    @Test
    fun `after close, a caller's line is dropped and counted, and the source's own thread runs its task in place`() {
        val source = source()
        source.close()
        val ranHere = AtomicBoolean()
        val ranOnOwn = AtomicBoolean()

        source.threads.offHousekeeping(HousekeepingTask.LOAD_LOG, { "after close" }) { ranHere.set(true) }
        val own = Thread {
            source.threads.onOwnThread.set(true)
            source.threads.offHousekeeping(HousekeepingTask.LOAD_LOG, { "after close, own" }) { ranOnOwn.set(true) }
        }
        own.start()
        own.join(TimeUnit.SECONDS.toMillis(WITHIN_SECONDS))

        ranHere.get().assert().isFalse()
        ranOnOwn.get().assert().isTrue()
        refused.toList().assert().isEqualTo(listOf(HousekeepingTask.LOAD_LOG))
    }

    /**
     * Close ends every load before it stops housekeeping: a compilation that ends later finds no load to install into,
     * so it reports nothing, and nothing is refused.
     */
    @Test
    fun `a compilation that ends after housekeeping stopped finds its load ended, and nothing is refused`() {
        val source = source(compileExecutor = null)
        val gate = CountDownLatch(1)
        compiler.gate = gate
        compiler.failure = IllegalStateException("no such matcher")
        val answer = source.load("app-a")
        awaitCondition { compiler.threads.isNotEmpty() }
        val closer = Thread { source.close() }
        closer.start()
        awaitCondition { housekeeping.isShutdown }

        gate.countDown()
        closer.join(TimeUnit.SECONDS.toMillis(TEST_LIMIT_SECONDS))

        answer.isCompletedExceptionally.assert().isTrue()
        refused.assert().isEmpty()
        lines.events.filter { "does not compile" in it.formattedMessage }.map { it.threadName }.assert().isEmpty()
    }

    @Test
    fun `nothing refused, nothing summarized`() {
        source().closing { source ->
            source.load("app-a").get(WITHIN_SECONDS, TimeUnit.SECONDS)
            source.tick().get(TEST_LIMIT_SECONDS, TimeUnit.SECONDS)
            awaitCondition { housekeeping.queue.isEmpty() && housekeeping.activeCount == 0 }

            summaries().assert().isEmpty()
            refused.assert().isEmpty()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(TEST_LIMIT_SECONDS).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within $TEST_LIMIT_SECONDS seconds" }
            Thread.sleep(5)
        }
    }

    private companion object {
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
        const val HOUSEKEEPING = "cosec-snapshot-housekeeping"
        const val TICKS = 3
        const val CLOSE_TIMEOUT_MILLIS = 200L
        const val WITHIN_SECONDS = 3L
        const val TEST_LIMIT_SECONDS = 10L

        /** A line `SNP-19` keeps off a caller's thread: INFO or above, or with a stack. */
        fun covered(event: ILoggingEvent): Boolean =
            event.level.isGreaterOrEqual(Level.INFO) || event.throwableProxy != null
    }
}
