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
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A request's cold load timeout over the runner: armed when its offer returns, unless the request is already answered,
 * with what is left of the bound since the call. Registered before the offer, so `close` answers it whatever the drain
 * does; an offer that throws leaves it answered and untimed.
 */
class KeyRunnerRequestTimerTest {
    /** Records every `schedule`, and keeps what it was given instead of running it: the test fires a timer itself. */
    private class RecordingScheduler : ScheduledThreadPoolExecutor(1) {
        val scheduled = CopyOnWriteArrayList<Pair<Long, Runnable>>()

        /** While set, a request timer is refused, as by a scheduler that has shut down. */
        @Volatile
        var refusing = false

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (refusing && unit.toNanos(delay) <= TIMEOUT.toNanos()) throw RejectedExecutionException("refused")
            scheduled += unit.toNanos(delay) to command
            return super.schedule({}, 1, TimeUnit.DAYS)
        }

        /** The request timers: every delay up to `coldLoadTimeout` (a store read's timeout is 30 s). */
        fun timers(): List<Pair<Long, Runnable>> = scheduled.filter { it.first <= TIMEOUT.toNanos() }
    }

    private val backing = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val nanoTime = ManualNanoTime()

    /** How far the source's time moves while a read of app A starts: the offer returns that much after the call. */
    @Volatile
    private var readTakes: Duration = Duration.ZERO
    private val store = object : SnapshotDocumentStore by backing {
        override fun read(key: SnapshotKey) = backing.read(key).also { slow(key) }

        override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?) =
            backing.readIfChanged(key, known).also { slow(key) }

        private fun slow(key: SnapshotKey) {
            if (key == APP_A) nanoTime.advance(readTakes)
        }
    }
    private val metrics = RecordingMetrics()
    private val scheduler = RecordingScheduler()
    private val requests = CopyOnWriteArrayList<CompletableFuture<*>>()
    private val throwAfterRelease = AtomicBoolean()

    /** The source's slot context, taken as it makes its first slot: where its registered requests are. */
    @Volatile
    private var context: SlotContext? = null

    private fun registered() = requireNotNull(context).pendingRequests

    private fun source(): SnapshotSource<Compiled, Compiled> {
        val hooks = KeyDrain.Hooks(
            beforeNext = { event ->
                if (event is KeyEvent.Requested && event.ask is KeyEvent.Ask.Request) requests += event.waiter as CompletableFuture<*>
            },
            afterRelease = { if (throwAfterRelease.getAndSet(false)) throw IllegalStateException("the offer threw") },
        )
        return SnapshotSource(
            store,
            SnapshotKey.Platform,
            FakeCompiler(),
            listOf(SnapshotFamily(SnapshotKind.APP, FakeCompiler(), OPTIONS.maxApps, OPTIONS.maxAbsent)),
            OPTIONS,
            ManualClock(),
            metrics,
            directExecutor,
            scheduler,
            { 0L },
            null,
            capturing(keyRunners(hooks = hooks, hooked = { it == APP_A })),
        ).also {
            it.nanoTime = nanoTime
            it.load().join()
        }
    }

    private fun capturing(runners: SlotFactory) = object : SlotFactory {
        override fun <T : Any> create(
            key: SnapshotKey,
            compiler: SnapshotCompiler<T>,
            context: SlotContext
        ): KeySlot<T> {
            this@KeyRunnerRequestTimerTest.context = context
            return runners.create(key, compiler, context)
        }
    }

    private fun appOutcomes() = metrics.loads.filter { it.first == SnapshotKind.APP }.map { it.second }

    @AfterEach
    fun stop() {
        scheduler.shutdownNow()
    }

    @Test
    fun `a request answered inside its offer, by a load that completes in the drain, schedules no timer`() {
        source().use { source ->
            source.load("app-a").isDone.assert().isTrue()
            appOutcomes().assert().containsExactly(LoadOutcome.COLD)
            scheduler.timers().assert().isEmpty()
        }
    }

    @Test
    fun `a hit schedules no timer`() {
        source().use { source ->
            source.load("app-a").join()
            scheduler.scheduled.clear()
            source.load("app-a").isDone.assert().isTrue()
            appOutcomes().assert().containsExactly(LoadOutcome.COLD, LoadOutcome.HIT)
            scheduler.timers().assert().isEmpty()
        }
    }

    @Test
    fun `a request whose offer returns late is timed by what is left of its bound, not the whole of it`() {
        source().use { source ->
            backing.hold = true
            readTakes = Duration.ofMillis(300)
            source.load("app-a").isDone.assert().isFalse()
            scheduler.timers().map { it.first }.assert().containsExactly(TIMEOUT.minusMillis(300).toNanos())
        }
    }

    @Test
    fun `a request whose bound is spent when its offer returns is answered TIMEOUT at once, on the caller's thread`() {
        source().use { source ->
            backing.hold = true
            readTakes = TIMEOUT
            val answer = source.load("app-a")
            answer.isDone.assert().isTrue()
            answer.join().app.assert().isInstanceOf(DocumentState.Failed::class.java)
            appOutcomes().assert().containsExactly(LoadOutcome.TIMEOUT)
            scheduler.timers().assert().isEmpty()
        }
    }

    @Test
    fun `an offer that throws leaves its request answered, not registered and waiting`() {
        source().use { source ->
            backing.hold = true
            throwAfterRelease.set(true)
            assertThrows<IllegalStateException> { source.load("app-a") }
            val request = requests.single()
            request.isCompletedExceptionally.assert().isTrue()
            registered().assert().isEmpty()
        }
    }

    @Test
    fun `an offer that throws arms no timer`() {
        source().use { source ->
            backing.hold = true
            throwAfterRelease.set(true)
            assertThrows<IllegalStateException> { source.load("app-a") }
            scheduler.timers().assert().isEmpty()
        }
    }

    @Test
    fun `a request whose timer the scheduler refuses is answered FAILED once, unregistered, and its load's end changes nothing`() {
        source().use { source ->
            backing.hold = true
            scheduler.refusing = true
            val answer = source.load("app-a")
            answer.isDone.assert().isTrue()
            answer.join().app.assert().isInstanceOf(DocumentState.Failed::class.java)
            appOutcomes().assert().containsExactly(LoadOutcome.FAILED)
            registered().assert().isEmpty()
            // The load goes on, and ends: the request was answered already.
            backing.pending.single { it.key == APP_A }.release()
            source.holds(APP_A).assert().isTrue()
            answer.join().app.assert().isInstanceOf(DocumentState.Failed::class.java)
            appOutcomes().assert().containsExactly(LoadOutcome.FAILED)
        }
    }

    @Test
    fun `a request still loading when the source closes is answered by close`() {
        val source = source()
        backing.hold = true
        val answer = source.load("app-a")
        answer.isDone.assert().isFalse()
        source.close()
        answer.isDone.assert().isTrue()
    }

    @Test
    fun `a timer that fires after the answer, before its cancel, finds the request answered and does nothing`() {
        source().use { source ->
            backing.hold = true
            val answer = source.load("app-a")
            val timer = scheduler.timers().single().second
            backing.pending.single { it.key == APP_A }.release()
            answer.join().app.assert().isInstanceOf(DocumentState.Present::class.java)
            // As if the scheduler ran it between the answer and the cancel that follows it.
            timer.run()
            answer.join().app.assert().isInstanceOf(DocumentState.Present::class.java)
            appOutcomes().assert().containsExactly(LoadOutcome.COLD)
        }
    }

    private companion object {
        val TIMEOUT: Duration = SnapshotOptions().coldLoadTimeout
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS)
    }
}
