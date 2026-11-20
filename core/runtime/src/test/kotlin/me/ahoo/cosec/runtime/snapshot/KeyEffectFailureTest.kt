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
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What a key does when its effects throw (RFC 0013 B2b, the load lane): a step offers one `EffectFailed`, its first
 * failure with the later ones suppressed in it, and an `EffectFailed`'s own step offers none, so no chain of them runs
 * on a caller's thread or on the compile pool, where a `Log` that always throws used to loop. A `VirtualMachineError` is
 * kept through both: rethrown on the compile pool, logged at ERROR on any other thread.
 */
class KeyEffectFailureTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
        put(APP_B, document(1))
    }
    private val compiler = FakeCompiler()
    private val lines = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(SnapshotSource::class.java.name) as Logger
    private val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private val previous = Thread.getDefaultUncaughtExceptionHandler()
    private val failed = CopyOnWriteArrayList<KeyEvent.EffectFailed>()
    private val housekeeping = Housekeeping()

    /** A's last step and the last release of its flag, in one order ([taking], [released]). */
    private val sequence = AtomicLong()

    @Volatile
    private var lastStepOfA = 0L

    @Volatile
    private var lastReleaseOfA = 0L

    /** The thread in A's drain, from its step to the release of its flag; null outside. */
    @Volatile
    private var drainerOfA: Thread? = null

    @Volatile
    private var installFails: Throwable? = null
    private val metrics = object : SnapshotMetrics {
        override fun onInstall(entry: SnapshotEntry<*>) {
            if (entry.key == APP_A) installFails?.let { throw it }
        }
    }

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

    private fun source(compileExecutor: Executor?) = SnapshotSource(
        store,
        SnapshotKey.Platform,
        compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, OPTIONS.maxApps, OPTIONS.maxAbsent)),
        OPTIONS,
        ManualClock(),
        metrics,
        compileExecutor,
        null,
        { 0L },
        housekeeping,
        keyRunners(hooks = KeyDrain.Hooks(beforeNext = ::taking, afterRelease = ::released)) { it == APP_A },
    )

    /**
     * Records each `EffectFailed`. The first event after one disarms what the case made throw: the step under test and
     * the `EffectFailed`'s own step fail, and A's later loads, each of whose ends asks for the pass, do not.
     */
    private fun taking(event: KeyEvent<*>) {
        lastStepOfA = sequence.incrementAndGet()
        drainerOfA = Thread.currentThread()
        if (event is KeyEvent.EffectFailed) {
            failed += event
        } else if (failed.isNotEmpty()) {
            installFails = null
            housekeeping.failing = { null }
        }
    }

    private fun released() {
        drainerOfA = null
        lastReleaseOfA = sequence.incrementAndGet()
    }

    /**
     * A holds nothing in flight and no thread is in its drain: its flag was released after its last step. Not [KeyRunner.idle]
     * alone, which the drain publishes with the step's state, before it runs the step's effects.
     */
    private fun SnapshotSource<*, *>.quietA(): Boolean = slotA().idle() && lastReleaseOfA > lastStepOfA

    private fun SnapshotSource<*, *>.slotA(): KeyRunner<*> =
        families.first { it.spec.kind == SnapshotKind.APP }.slots.getValue("app-a")

    /**
     * App A installed, then changed to revision 2 with [body] once [arm] has set what throws. On the compile pool
     * ([compileExecutor] null) the compilation waits until the change's drain has returned, so the pool takes its own
     * result, as a pool thread drains: unbounded.
     */
    private fun reloaded(
        compileExecutor: Executor?,
        body: String = "p2",
        arm: () -> Unit
    ): SnapshotSource<Compiled, Compiled> {
        val source = source(compileExecutor)
        // Two apps over a bound of one, which the passes housekeeping drops never take back: each install of A asks
        // housekeeping for the pass, the effect these cases make throw.
        source.load("app-b").join()
        source.load("app-a").join()
        // A's first load is followed by a confirming one. Armed once A is quiet: A reads idle as soon as that load's end
        // is stored, while the pool still runs its effects, its hand to housekeeping last. Armed in that window, the hand
        // fails first, and its `EffectFailed` disarms the meter before revision 2 is installed (run 44229457825).
        awaitCondition { source.quietA() }
        arm()
        // On the pool only: compiled in place, a held compilation would hold this thread until the gate times out.
        val gate = CountDownLatch(1).also { if (compileExecutor == null) compiler.gate = it }
        // The store tells the source of the change, and the key reloads.
        store.put(APP_A, document(2, body))
        gate.countDown()
        awaitCondition { source.slotA().installed?.revision == 2L }
        return source
    }

    @Test
    fun `every effect of a step that throws gives one EffectFailed, and its own failed log gives none`() {
        failingStep(compileExecutor = directExecutor)
        failed.single().cause.message.assert().isEqualTo("housekeeping 1")
        failed.single().cause.suppressed.map { it.message }.assert().containsExactly("housekeeping 2")
    }

    @Test
    fun `an EffectFailed whose log throws gives no other on the compile pool, which drains unbounded`() {
        failingStep(compileExecutor = null)
        failed.assert().hasSize(1)
    }

    /**
     * App A's revision 2 fails to compile, in a step where the compile report's line and the eviction pass both go to
     * housekeeping, which throws; then its `EffectFailed`'s step logs through housekeeping, which throws again. Before
     * the lane's fix that log raised another `EffectFailed`, whose log raised another, without end: close would wait on
     * the key for good.
     */
    private fun failingStep(compileExecutor: Executor?) {
        val calls = AtomicInteger()
        val source = reloaded(compileExecutor, body = "THROW") {
            housekeeping.failing = { IllegalStateException("housekeeping ${calls.incrementAndGet()}") }
        }
        awaitCondition { failed.isNotEmpty() }
        assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS)) { source.close() }
    }

    @Test
    fun `a VirtualMachineError in a step with other failures is still logged on a caller's thread`() {
        reloaded(directExecutor, arm = ::installOverflows).use {
            failed.single().cause.assert().isInstanceOf(StackOverflowError::class.java)
            failed.single().cause.suppressed.single().message.assert().isEqualTo("housekeeping")
            awaitCondition {
                lines.captured().any { "the effect Install threw java.lang.StackOverflowError" in it.formattedMessage }
            }
            uncaught.assert().isEmpty()
        }
    }

    @Test
    fun `a VirtualMachineError in a step with other failures is still rethrown on the compile pool`() {
        reloaded(compileExecutor = null, arm = ::installOverflows).use {
            awaitCondition { uncaught.isNotEmpty() }
            uncaught.single().first.assert().startsWith("cosec-snapshot-compile")
            uncaught.single().second.assert().isInstanceOf(StackOverflowError::class.java)
            failed.single().cause.suppressed.single().message.assert().isEqualTo("housekeeping")
        }
    }

    /** In one step the install meter overflows and the eviction pass after it fails; the crash's own line still goes. */
    private fun installOverflows() {
        installFails = StackOverflowError("install meter")
        housekeeping.failing = {
            val evicting = Thread.currentThread().stackTrace.any { it.methodName == "evictBeyond" }
            if (evicting) IllegalStateException("housekeeping") else null
        }
    }

    @Test
    fun `what an EffectFailed's own step fails is logged in place, and no other EffectFailed comes`() {
        reloaded(directExecutor) {
            // The eviction pass fails, so an EffectFailed comes; its step's log effect fails too, and is logged in place.
            housekeeping.failing = {
                val inPlace = Thread.currentThread().stackTrace.any { it.methodName == "loggedNotRaised" }
                if (inPlace) null else IllegalStateException("housekeeping")
            }
        }.use {
            failed.assert().hasSize(1)
            awaitCondition {
                lines.captured().any { "an effect failed while a failed effect was handled" in it.formattedMessage }
            }
        }
    }

    @Test
    fun `a VirtualMachineError from logging what an EffectFailed's step failed is kept and logged, not dropped`() {
        reloaded(directExecutor) {
            // The eviction pass fails, so an EffectFailed comes; its step's log fails, and its log in place overflows.
            housekeeping.failing = {
                val stack = Thread.currentThread().stackTrace
                when {
                    stack.any { it.methodName == "loggedNotRaised" } -> StackOverflowError("logging")
                    stack.any { it.methodName == "report" && it.className.endsWith("KeyDrain") } -> null
                    else -> IllegalStateException("housekeeping")
                }
            }
        }.use {
            failed.assert().hasSize(1)
            awaitCondition {
                lines.captured().any { "logging a failed effect threw java.lang.StackOverflowError" in it.formattedMessage }
            }
            uncaught.assert().isEmpty()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "condition not met within $WAIT_SECONDS seconds; EffectFailed taken: " +
                    failed.map { "${it.cause} (suppressed ${it.cause.suppressed.toList()})" } +
                    "; uncaught: ${uncaught.map { "${it.first}: ${it.second}" }}; lines: " +
                    lines.captured().map { "[${it.threadName}] ${it.formattedMessage} ${it.throwableProxy?.className ?: ""}" }
            }
            Thread.sleep(1)
        }
    }

    @Test
    fun `A reads idle while the pool still runs its load end's effects, and is quiet only once its flag is released`() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        source(compileExecutor = null).use { source ->
            source.load("app-b").join()
            // A hand to housekeeping made inside A's drain while A already reads idle: its confirming load's end. Not
            // app-b's tail, which another pool thread may still be running.
            housekeeping.holdOnPool = {
                if (drainerOfA === Thread.currentThread() && source.slotA().idle()) release.also { held.countDown() } else null
            }
            // The compilation waits until this thread's drain of A has returned, so the pool takes its own result.
            val gate = CountDownLatch(1).also { compiler.gate = it }
            val loading = source.load("app-a")
            gate.countDown()
            loading.join()
            held.await(WAIT_SECONDS, TimeUnit.SECONDS).assert().isTrue()
            source.slotA().idle().assert().isTrue()
            source.quietA().assert().isFalse()
            release.countDown()
            awaitCondition { source.quietA() }
        }
    }

    /**
     * Housekeeping on the calling thread; while [failing] answers an error, it throws that instead of running. It drops
     * the eviction passes it is not told to fail, so the family stays above its bound.
     */
    private class Housekeeping : AbstractExecutorService() {
        @Volatile
        var failing: () -> Throwable? = { null }

        /** On a pool thread, a latch to wait on before anything else, when it answers one: where a case holds the pool. */
        @Volatile
        var holdOnPool: () -> CountDownLatch? = { null }

        override fun execute(command: Runnable) {
            if (Thread.currentThread().name.startsWith("cosec-snapshot-compile")) {
                holdOnPool()?.let {
                    holdOnPool = { null }
                    it.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
            failing()?.let { throw it }
            if (Thread.currentThread().stackTrace.none { it.methodName == "evictBeyond" }) command.run()
        }

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private companion object {
        val APP_A = SnapshotKey.App("app-a")
        val APP_B = SnapshotKey.App("app-b")
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = 1)
        const val WAIT_SECONDS = 10L
    }
}
