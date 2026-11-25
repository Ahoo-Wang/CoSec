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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotStaleException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier

/**
 * Lost and early notifications (F9), resets (F10), revision regressions (F11), per-tenant fail-closed (F15), and the
 * bounds of the periodic pass that recovers from a lost notification (`SNP-6`, RC1-50): one pass at a time (#1215).
 */
class SnapshotRevalidationTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1, "a1"))
        put(APP_B, document(1, "b1"))
    }

    @Test
    fun f9LostNotificationConvergesOnRevalidation() {
        testSource(store).use { source ->
            source.load("app-a").join()
            store.dropNotifications = true
            store.put(SnapshotKey.Platform, document(2))
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)

            source.revalidate().join()

            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun f9NotificationBeforeTheChangeIsReadableConvergesOnPeriodicRevalidation() {
        val options = SnapshotOptions(revalidateInterval = SnapshotOptions.MIN_REVALIDATE_INTERVAL)
        appSource(
            store,
            FakeCompiler(),
            FakeCompiler(),
            options,
            Clock.systemUTC(),
            SnapshotMetrics.NOOP
        ).use { source ->
            source.load().join()
            // The listener runs before the store serves the new value: the reload it starts reads revision 1. Its read
            // is held, its answer fixed when it starts, and revision 2 is put only once it has started: else, on a
            // loaded machine, the reload could read after the put and install revision 2 with no periodic pass at all.
            store.hold = true
            store.notifyChanged(SnapshotKey.Platform)
            await { store.pending.any { it.key == SnapshotKey.Platform } }
            store.dropNotifications = true
            store.put(SnapshotKey.Platform, document(2))
            store.hold = false
            store.releaseAll()

            // Only a periodic pass reads the store again: the notification of revision 2 was lost.
            await { (source.peek()?.policies as? DocumentState.Present)?.compiled?.revision == 2L }
        }
    }

    /**
     * SNP-13: revalidating while a load is in flight does not answer with that load, which read before the call and
     * before what was published just before it, but with a read that starts after the call.
     */
    @Test
    fun revalidationAnswersWithAReadThatStartedAfterTheCall() {
        testSource(store).use { source ->
            source.load().join()
            store.dropNotifications = true
            store.hold = true
            // A load in flight, its read made while the store still holds revision 1: started before the put, or the
            // case would pass with a read of revision 2 and say nothing.
            val earlier = source.revalidate()
            await { store.pending.any { it.key == SnapshotKey.Platform } }
            store.put(SnapshotKey.Platform, document(2))
            val revalidated = source.revalidate()
            store.hold = false
            store.pending.toList().forEach {
                store.pending.remove(it)
                it.release()
            }
            earlier.join()
            revalidated.join()

            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    /**
     * `SNP-6`: a change written to the store before a pass starts is installed by the time that pass has finished with
     * its document, though a load of it was in flight when the pass reached it. That load's read answered revision 1
     * before the change; a pass never counts a store read that began before it did as its confirmation.
     */
    @Test
    fun aPassInstallsAChangeWrittenBeforeItThoughALoadThatReadBeforeItWasInFlight() {
        val elapsed = ManualNanoTime()
        testSource(store).use { source ->
            source.nanoTime = elapsed
            source.load("app-a").join()
            store.dropNotifications = true
            store.hold = true
            // A load in flight, its read made while the store still holds revision 1.
            val earlier = source.reload(APP_A)
            store.put(APP_A, document(2, "a2"))
            elapsed.advance(Duration.ofSeconds(1))

            val pass = source.tick()
            pass.isDone.assert().isFalse()
            store.hold = false
            store.releaseAll()

            (earlier.join().state as DocumentState.Present).compiled.assert().isInstanceOf(Compiled::class.java)
            pass.isDone.assert().isTrue()
            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(2)
        }
    }

    /**
     * `SNP-6`: the load after one older than the pass is the pass's read, counted as its read in flight. A tick that
     * comes once the pass has finished no document for `storeReadTimeout` does not abandon it while that read is in
     * flight (`a pass with a read in flight never is`), so its reads and the next pass's never add up.
     */
    @Test
    fun theLoadAfterAnOlderOneIsThePassReadSoThePassIsNotAbandonedWhileItReads() {
        val elapsed = ManualNanoTime()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 1)
        testSource(store, options = options).use { source ->
            source.nanoTime = elapsed
            source.load("app-a").join()
            store.dropNotifications = true
            store.hold = true
            val earlier = source.reload(APP_A)
            store.put(APP_A, document(2, "a2"))
            elapsed.advance(Duration.ofSeconds(1))

            val pass = source.tick()
            // The one lane reads the shared document first, then meets the older load of app-a and waits after it.
            store.pending.single { it.key == SnapshotKey.Platform }.let { read ->
                store.pending.remove(read)
                read.release()
            }
            store.pending.single { it.key == APP_A }.let { read ->
                store.pending.remove(read)
                read.release()
            }
            earlier.join()
            // The load after it reads now, for the pass.
            store.pending.single().key.assert().isEqualTo(APP_A)
            val reads = store.readLog.size

            elapsed.advance(options.storeReadTimeout.plusSeconds(1))
            val owed = source.tick()
            source.tick().assert().isSameAs(owed)
            store.readLog.size.assert().describedAs("reads after the stalled-looking tick").isEqualTo(reads)
            pass.isDone.assert().isFalse()

            store.hold = false
            store.releaseAll()
            pass.isDone.assert().isTrue()
            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(2)
        }
    }

    /**
     * `SNP-6`: a pass that has finished no document for `storeReadTimeout` and has no read in flight is abandoned at
     * the next tick, even when the compilation that holds it is younger than that. Here the pass meets a load older than
     * itself and waits for the load after it, which starts twenty seconds after the pass last finished a document and
     * then never ends compiling. Its compile deadline (R2D-11) is `storeReadTimeout` after that load began, so the
     * tick just past `storeReadTimeout` since the pass's last progress finds no compilation to sweep: only abandoning
     * the pass lets the next one start, and read the documents again, at that tick.
     */
    @Test
    fun aPassHeldByACompilationYoungerThanItsStallIsAbandonedBeforeThatCompilationsDeadline() {
        val parked = CopyOnWriteArrayList<Runnable>()
        val stuck = AtomicBoolean()
        val compile = Executor { if (stuck.get()) parked += it else it.run() }
        val elapsed = ManualNanoTime()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 1)
        testSource(store, options = options, compileExecutor = compile).use { source ->
            source.nanoTime = elapsed
            source.load("app-a").join()
            store.dropNotifications = true
            store.hold = true
            val earlier = source.reload(APP_A)
            store.put(APP_A, document(2, "a2"))
            elapsed.advance(Duration.ofSeconds(1))

            val pass = source.tick()
            // The shared document finishes now: the pass's last progress.
            store.pending.single { it.key == SnapshotKey.Platform }.let { read ->
                store.pending.remove(read)
                read.release()
            }
            // Twenty seconds later the older load of app-a reads, and the load after it, the pass's, starts.
            elapsed.advance(Duration.ofSeconds(20))
            store.pending.single { it.key == APP_A }.let { read ->
                store.pending.remove(read)
                read.release()
            }
            earlier.join()
            // The pass's read answers, and its compilation never ends.
            stuck.set(true)
            store.pending.single().let { read ->
                read.key.assert().isEqualTo(APP_A)
                store.pending.remove(read)
                read.release()
            }
            parked.assert().hasSize(1)
            val platformReads = store.readLog.count { it == SnapshotKey.Platform }

            // 31 s since the last progress (at 1 s), past storeReadTimeout; 11 s since that load began (at 21 s), so
            // its compile deadline is 19 s away.
            elapsed.advance(options.storeReadTimeout.minusSeconds(19))
            source.tick()
            store.readLog.count { it == SnapshotKey.Platform }.assert()
                .describedAs("the next pass reads the shared document again").isEqualTo(platformReads + 1)
            pass.isDone.assert().isFalse()

            store.hold = false
            store.releaseAll()
            stuck.set(false)
            parked.forEach { it.run() }
            pass.isDone.assert().isTrue()
            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(2)
        }
    }

    /**
     * `SNP-6`: a pass does not wait for a load stuck compiling past `storeReadTimeout`. That load read revision 1 before
     * the pass started, so it confirms nothing for the pass: one more load still follows it, and revision 2, written
     * before the pass with its invalidation lost, is installed with no later pass. Since R2D-11 the stuck load ends at
     * that tick, its compilation past the reload's budget and abandoned, so the load that follows reads at once.
     */
    @Test
    fun aPassThatSkipsALoadStuckCompilingStillOwesTheReadAfterIt() {
        val parked = CopyOnWriteArrayList<Runnable>()
        val stuck = AtomicBoolean()
        val compile = Executor { if (stuck.get()) parked += it else it.run() }
        val elapsed = ManualNanoTime()
        testSource(store, compileExecutor = compile).use { source ->
            source.nanoTime = elapsed
            source.load("app-a").join()
            stuck.set(true)
            source.reload(APP_A)
            parked.assert().hasSize(1)
            store.dropNotifications = true
            store.put(APP_A, document(2, "a2"))
            elapsed.advance(SnapshotOptions().storeReadTimeout.plusSeconds(1))
            stuck.set(false)

            // The pass ends without waiting for the compilation: never a join here, which would hang where it waits.
            source.tick().isDone.assert().isTrue()
            // The tick ended the stuck load (R2D-11), keeping revision 1 (F6); the load owed after it read revision 2.
            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(2)

            // The abandoned compilation never starts once it leaves the queue: it installs nothing over revision 2.
            parked.removeAt(0).run()
            source.peek("app-a")!!.appCompiled.revision.assert().isEqualTo(2)
        }
    }

    /**
     * DR1-D-5: a tick that throws still sweeps the compile deadlines. A reload has been stuck compiling past
     * `storeReadTimeout` when the tick's own clock read fails: the tick fails, and the stuck load ends all the same.
     */
    @Test
    fun aTickThatThrowsStillEndsTheLoadsPastTheirCompileDeadline() {
        val parked = CopyOnWriteArrayList<Runnable>()
        val stuck = AtomicBoolean()
        val compile = Executor { if (stuck.get()) parked += it else it.run() }
        val elapsed = ManualNanoTime()
        testSource(store, compileExecutor = compile).use { source ->
            source.nanoTime = elapsed
            source.load("app-a").join()
            stuck.set(true)
            val reload = source.reload(APP_A)
            parked.assert().hasSize(1)
            elapsed.advance(SnapshotOptions().storeReadTimeout.plusSeconds(1))
            val caller = Thread.currentThread()
            val armed = AtomicBoolean(true)
            source.nanoTime = LongSupplier {
                check(Thread.currentThread() !== caller || !armed.compareAndSet(true, false)) { "The clock failed." }
                elapsed.asLong
            }

            assertThrows<IllegalStateException> { source.tick() }

            armed.get().assert().describedAs("the tick read the clock and failed").isFalse()
            reload.isDone.assert().describedAs("the stuck load ended though the tick threw").isTrue()
            stuck.set(false)
            parked.removeAt(0).run()
        }
    }

    @Test
    fun f9NotificationDuringALoadStartsAnotherOnceItCompletes() {
        testSource(store).use { source ->
            store.hold = true
            val cold = source.load()
            store.put(SnapshotKey.Platform, document(2))
            store.pending.removeAt(0).release()
            cold.join().policiesCompiled.revision.assert().isEqualTo(1)

            store.pending.assert().hasSize(1)
            store.releaseAll()
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun f10ResetReloadsEveryInstalledDocument() {
        testSource(store).use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            store.dropNotifications = true
            store.put(SnapshotKey.Platform, document(2))
            store.put(APP_A, document(2, "a2"))
            store.put(APP_B, document(2, "b2"))

            val conditionalReads = store.conditionalReads.get()
            store.reset()

            source.peek("app-a")!!.revisions().assert().isEqualTo(2L to 2L)
            source.peek("app-b")!!.revisions().assert().isEqualTo(2L to 2L)
            store.conditionalReads.get().assert().isEqualTo(conditionalReads + 3)
        }
    }

    @Test
    fun f10ResetDuringALoadReloadsAfterIt() {
        testSource(store).use { source ->
            source.load().join()
            store.dropNotifications = true
            store.hold = true
            source.revalidate()
            // The load's read starts before the put, so revision 2 can come only from the reload the reset owes.
            await { store.pending.any { it.key == SnapshotKey.Platform } }
            store.put(SnapshotKey.Platform, document(2))
            store.reset()
            store.releaseAll()
            store.releaseAll()
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun f11LowerRevisionIsInstalledAndReported() {
        val metrics = RecordingMetrics()
        store.put(SnapshotKey.Platform, document(5))
        testSource(store, metrics = metrics).use { source ->
            source.load().join()

            store.put(SnapshotKey.Platform, document(3))

            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(3)
            metrics.regressions.assert().containsExactly(Triple(SnapshotKey.Platform, 5L, 3L))

            store.put(SnapshotKey.Platform, document(4))
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(4)
            metrics.regressions.assert().hasSize(1)
        }
    }

    @Test
    fun f11SameRevisionWithOtherContentIsInstalledAndReported() {
        val metrics = RecordingMetrics()
        testSource(store, metrics = metrics).use { source ->
            source.load().join()

            store.put(SnapshotKey.Platform, document(1, "rewritten"))

            source.peek()!!.policiesCompiled.entries.assert().containsExactly("rewritten")
            metrics.regressions.assert().containsExactly(Triple(SnapshotKey.Platform, 1L, 1L))
        }
    }

    @Test
    fun f11RegressionIsDetectedAcrossEviction() {
        val metrics = RecordingMetrics()
        store.put(APP_A, document(7, "a7"))
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = 1)
        testSource(store, options = options, metrics = metrics).use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            await { !source.holds(APP_A) }
            store.put(APP_A, document(6, "a6"))

            source.load("app-a").join().appCompiled.revision.assert().isEqualTo(6)
            metrics.regressions.assert().containsExactly(Triple(APP_A, 7L, 6L))
        }
    }

    @Test
    fun f15FailClosedTenantFailsOnceLagPassesItsBound() {
        val clock = ManualClock()
        val options = SnapshotOptions(
            revalidateInterval = QUIET_PASS,
            failClosedTenants = setOf("strict"),
            failClosedMaxLag = Duration.ofMinutes(30),
        )
        testSource(store, options = options, clock = clock).use { source ->
            source.load("app-a").join()
            store.failure = IllegalStateException("redis down")
            clock.advance(Duration.ofMinutes(30))
            source.revalidate().join()

            assertDoesNotThrow { source.checkFresh(source.peek("app-a")!!, "strict") }

            clock.advance(Duration.ofSeconds(1))
            val error = assertThrows<SnapshotStaleException> { source.checkFresh(source.peek("app-a")!!, "strict") }
            error.tenantId.assert().isEqualTo("strict")
            error.lag.assert().isEqualTo(Duration.ofMinutes(30).plusSeconds(1))
            assertDoesNotThrow { source.checkFresh(source.peek("app-a")!!, "relaxed") }

            store.failure = null
            source.revalidate().join()
            assertDoesNotThrow { source.checkFresh(source.peek("app-a")!!, "strict") }
        }
    }

    @Test
    fun f15LagIsTheOlderOfTheTwoDocuments() {
        val clock = ManualClock()
        val options =
            SnapshotOptions(
                revalidateInterval = QUIET_PASS,
                failClosedTenants = setOf("strict"),
                failClosedMaxLag = Duration.ofMinutes(1)
            )
        testSource(store, options = options, clock = clock).use { source ->
            source.load().join()
            clock.advance(Duration.ofMinutes(2))
            source.load("app-a").join()

            source.peek("app-a")!!.lagAt(clock.now).assert().isEqualTo(Duration.ofMinutes(2))
            assertThrows<SnapshotStaleException> { source.checkFresh(source.peek("app-a")!!, "strict") }
        }
    }

    @Test
    fun noFailClosedTenantsMeansNoCheck() {
        val clock = ManualClock()
        testSource(store, clock = clock).use { source ->
            source.load().join()
            clock.advance(Duration.ofDays(365))
            assertDoesNotThrow { source.checkFresh(source.peek()!!, "any") }
        }
    }

    /** `SNP-6`: revalidation asks the store of record, never a local copy that may predate the change it missed. */
    @Test
    fun revalidationAsksTheStoreNeverALocalCopy() {
        testSource(store).use { source ->
            source.load().join()
            store.dropNotifications = true
            store.localCopies[SnapshotKey.Platform] = document(1)
            store.put(SnapshotKey.Platform, document(2))

            source.revalidate().join()

            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    /** RC1-50: the periodic pass is the only bound on a lost invalidation, so it can be neither off nor unbounded. */
    @Test
    fun theRevalidationIntervalCannotTurnThePassOffOrUnbindIt() {
        assertThrows<IllegalArgumentException> { SnapshotOptions(revalidateInterval = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(revalidateInterval = Duration.ofSeconds(-1)) }
        // A floor of a second (review L1 of #1200): below a millisecond the schedule could not even run.
        assertThrows<IllegalArgumentException> { SnapshotOptions(revalidateInterval = Duration.ofNanos(500_000)) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(revalidateInterval = Duration.ofMillis(999)) }
        SnapshotOptions(revalidateInterval = SnapshotOptions.MIN_REVALIDATE_INTERVAL).revalidateInterval.assert()
            .isEqualTo(Duration.ofSeconds(1))
        val longest = SnapshotOptions.MAX_REVALIDATE_INTERVAL
        val lag = longest.multipliedBy(2)
        SnapshotOptions(revalidateInterval = longest, lagWarn = lag, lagEscalate = lag)
            .revalidateInterval.assert().isEqualTo(longest)
        assertThrows<IllegalArgumentException> {
            SnapshotOptions(revalidateInterval = longest.plusMillis(1), lagWarn = lag, lagEscalate = lag)
        }
        // A lag report must mean a missed pass, not the schedule.
        assertThrows<IllegalArgumentException> { SnapshotOptions(revalidateInterval = SnapshotOptions().lagWarn) }
        val failClosed = Duration.ofSeconds(30)
        SnapshotOptions(revalidateInterval = failClosed, failClosedMaxLag = failClosed)
            .revalidateInterval.assert().isEqualTo(failClosed)
        assertThrows<IllegalArgumentException> {
            SnapshotOptions(
                revalidateInterval = failClosed,
                failClosedTenants = setOf("strict"),
                failClosedMaxLag = failClosed
            )
        }
    }

    /** The platform document and [count] app documents, installed. */
    private fun installed(source: SnapshotSource<Compiled, Compiled>, count: Int): List<SnapshotKey.App> {
        val apps = (1..count).map { SnapshotKey.App("app-$it") }
        apps.forEach { store.put(it, document(1, it.appId)) }
        apps.forEach { source.load(it.appId).join() }
        return apps
    }

    /**
     * #1215: a tick while a pass is in flight opens no lanes of its own, however many intervals the pass takes, so a
     * slow store never sees more than `reloadConcurrency` revalidation reads; the pass it owes starts as soon as that
     * one ends, and revalidates every document again.
     */
    @Test
    fun aPeriodicPassNeverOverlapsAnotherAndTheOneItOwesFollowsIt() {
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 2)
        testSource(store, options = options).use { source ->
            val apps = installed(source, 6)
            store.hold = true
            store.readLog.clear()
            val first = source.tick()
            store.pending.size.assert().isEqualTo(2)
            // Move the pass on: one started now would read documents this one is not reading.
            repeat(2) { store.pending.removeAt(0).release() }
            store.pending.size.assert().isEqualTo(2)

            val owed = source.tick()
            source.tick().assert().isSameAs(owed)
            store.pending.size.assert().isEqualTo(2)
            var peak = 0
            while (!first.isDone) {
                store.pending.removeAt(0).release()
                peak = maxOf(peak, store.pending.size)
            }
            // The owed pass started when the first ended, with nobody ticking.
            owed.isDone.assert().isFalse()
            store.pending.size.assert().isEqualTo(2)
            while (!owed.isDone) {
                store.pending.removeAt(0).release()
                peak = maxOf(peak, store.pending.size)
            }
            peak.assert().isLessThanOrEqualTo(2)
            store.pending.assert().isEmpty()
            // Every installed document twice: once by the first pass, once by the pass it owed, and no more.
            val documents = listOf<SnapshotKey>(SnapshotKey.Platform) + apps
            store.readLog.groupingBy { it }.eachCount().assert().isEqualTo(documents.associateWith { 2 })
            // Idle again: the next tick starts a pass of its own.
            store.hold = false
            source.tick().join()
            store.readLog.size.assert().isEqualTo(3 * documents.size)
        }
    }

    /**
     * #1215, `SNP-6`: a pass that has finished no document for `storeReadTimeout` and has no read in flight is held only
     * by a compilation. The next tick starts a new pass, so a compilation that never ends does not hold back the
     * revalidation of the documents that pass reaches.
     */
    @Test
    fun aPassHeldOnlyByACompilationDoesNotHoldBackTheNext() {
        val parked = CopyOnWriteArrayList<Runnable>()
        val stuck = AtomicBoolean()
        val compile = Executor { if (stuck.get()) parked += it else it.run() }
        val elapsed = ManualNanoTime()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 2)
        testSource(store, options = options, compileExecutor = compile).use { source ->
            source.nanoTime = elapsed
            val apps = installed(source, 3)
            store.dropNotifications = true
            store.put(apps[0], document(2, "changed"))
            stuck.set(true)
            store.readLog.clear()
            val first = source.tick()
            first.isDone.assert().isFalse()
            parked.assert().hasSize(1)
            val others = listOf<SnapshotKey>(SnapshotKey.Platform, apps[1], apps[2])
            others.forEach { key -> store.readLog.count { it == key }.assert().isEqualTo(1) }

            // Slow, not stalled: the tick owes a pass and reads nothing.
            elapsed.advance(options.storeReadTimeout)
            val owed = source.tick()
            others.forEach { key -> store.readLog.count { it == key }.assert().isEqualTo(1) }

            elapsed.advance(Duration.ofSeconds(1))
            source.tick().assert().isSameAs(owed)
            others.forEach { key -> store.readLog.count { it == key }.assert().isEqualTo(2) }

            // Once the compilation ends, both passes end, and so does what was owed.
            stuck.set(false)
            parked.forEach { it.run() }
            first.isDone.assert().isTrue()
            owed.isDone.assert().isTrue()
            source.peek(apps[0].appId)!!.appCompiled.revision.assert().isEqualTo(2)
        }
    }

    /**
     * `SNP-6`, #1231's review (L4): a pass does not wait for a load that has been in flight longer than
     * `storeReadTimeout`, whoever started it. Only a compilation holds it by then, and it confirms its document when it
     * ends. With one lane and a compilation that never ends, each pass would stop at it and the documents behind it
     * would never be read; here every document is read although no compilation ends before the last tick.
     */
    @Test
    fun aPassDoesNotWaitForACompilationThatOutlivedTheStoreReadTimeout() {
        val parked = CopyOnWriteArrayList<Runnable>()
        val stuck = AtomicBoolean()
        val compile = Executor { if (stuck.get()) parked += it else it.run() }
        val elapsed = ManualNanoTime()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 1)
        testSource(store, options = options, compileExecutor = compile).use { source ->
            source.nanoTime = elapsed
            val apps = installed(source, 3)
            store.dropNotifications = true
            apps.forEach { store.put(it, document(2, "${it.appId}-2")) }
            stuck.set(true)
            store.readLog.clear()
            val first = source.tick()
            first.isDone.assert().isFalse()
            // One tick per document, each past `storeReadTimeout` after the last: the pass before it is abandoned.
            repeat(apps.size) {
                elapsed.advance(options.storeReadTimeout.plusSeconds(1))
                source.tick()
            }
            apps.forEach { key ->
                store.readLog.count { it == key }.assert().describedAs("reads of $key").isGreaterThanOrEqualTo(1)
            }

            stuck.set(false)
            while (parked.isNotEmpty()) {
                parked.removeAt(0).run()
            }
            first.isDone.assert().isTrue()
            apps.forEach { key -> source.peek(key.appId)!!.appCompiled.revision.assert().isEqualTo(2) }
        }
    }

    /**
     * #1215, `SNP-6`: stalled means no document finished for `storeReadTimeout`, counted from the last one, not from the
     * start. A pass whose compilations are slow but still finishing is not abandoned (it would start again from the
     * first document every `storeReadTimeout`, and the documents after it would never be reached), and a step of the
     * wall clock is not time passing.
     */
    @Test
    fun aPassThatKeepsFinishingDocumentsIsNotAbandoned() {
        val parked = CopyOnWriteArrayList<Runnable>()
        val stuck = AtomicBoolean()
        val compile = Executor { if (stuck.get()) parked += it else it.run() }
        val clock = ManualClock()
        val elapsed = ManualNanoTime()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 2)
        testSource(store, options = options, clock = clock, compileExecutor = compile).use { source ->
            source.nanoTime = elapsed
            val apps = installed(source, 3)
            store.dropNotifications = true
            store.put(SnapshotKey.Platform, document(2))
            apps.forEach { store.put(it, document(2, "${it.appId}-2")) }
            stuck.set(true)
            store.readLog.clear()
            val first = source.tick()
            // Both lanes wait for a compilation, with no read in flight.
            parked.assert().hasSize(2)
            store.readLog.assert().hasSize(2)

            // 20 s in, one compilation ends: the pass finished a document, and reads the next.
            elapsed.advance(Duration.ofSeconds(20))
            parked.removeAt(0).run()
            store.readLog.assert().hasSize(3)
            parked.assert().hasSize(2)

            // 31 s in, 11 s after its last document, and the wall clock steps an hour: slow, not stalled. The compilation
            // waiting since 0 s is past its load's budget (R2D-11): that load ends, and its lane reads the next document.
            elapsed.advance(Duration.ofSeconds(11))
            clock.advance(Duration.ofHours(1))
            val owed = source.tick()
            store.readLog.assert().hasSize(4)

            // Past `storeReadTimeout` after its last document: stalled, and the next pass starts.
            elapsed.advance(Duration.ofSeconds(20))
            source.tick().assert().isSameAs(owed)
            store.readLog.size.assert().isGreaterThan(3)

            stuck.set(false)
            while (parked.isNotEmpty()) {
                parked.removeAt(0).run()
            }
            first.isDone.assert().isTrue()
            owed.isDone.assert().isTrue()
        }
    }

    /**
     * #1215, `SNP-6`: a pass with a read in flight is never abandoned, whatever either clock says: not when the wall
     * clock steps forward, nor when a late tick comes after `storeReadTimeout` but before the timeouts of those reads
     * have fired. So the reads of an abandoned pass and of the pass after it never add up past `reloadConcurrency`.
     */
    @Test
    fun aPassWithAReadInFlightIsNeverAbandoned() {
        val clock = ManualClock()
        val elapsed = ManualNanoTime()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 2)
        testSource(store, options = options, clock = clock).use { source ->
            source.nanoTime = elapsed
            installed(source, 4)
            store.hold = true
            store.readLog.clear()
            val first = source.tick()
            repeat(2) { store.pending.removeAt(0).release() }
            store.pending.assert().hasSize(2)

            clock.advance(options.storeReadTimeout.multipliedBy(2))
            val owed = source.tick()
            store.pending.assert().hasSize(2)

            elapsed.advance(options.storeReadTimeout.plusSeconds(1))
            source.tick().assert().isSameAs(owed)
            store.pending.assert().hasSize(2)

            var peak = 0
            while (!owed.isDone) {
                store.pending.removeAt(0).release()
                peak = maxOf(peak, store.pending.size)
            }
            first.isDone.assert().isTrue()
            peak.assert().isLessThanOrEqualTo(2)
        }
    }
}
