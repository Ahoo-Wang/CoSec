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

import me.ahoo.cosec.runtime.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotStaleException
import me.ahoo.cosec.spi.snapshot.SnapshotVersion
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

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
            // The listener runs before the store serves the new value: the reload it starts still reads revision 1.
            store.notifyChanged(SnapshotKey.Platform)
            store.dropNotifications = true
            store.put(SnapshotKey.Platform, document(2))

            await { (source.peek()?.policies as? DocumentState.Present)?.compiled?.revision == 2L }
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

            source.peek("app-a")!!.version.assert().isEqualTo(SnapshotVersion(2, 2))
            source.peek("app-b")!!.version.assert().isEqualTo(SnapshotVersion(2, 2))
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
     * #1215, `SNP-6`: a pass that has finished no document for `storeReadTimeout` has no read in flight; what holds it
     * is a compilation. The next tick starts a new pass, so one compilation that never ends does not hold back the
     * revalidation of every other document.
     */
    @Test
    fun aPassHeldOnlyByACompilationDoesNotHoldBackTheNext() {
        val parked = CopyOnWriteArrayList<Runnable>()
        val stuck = AtomicBoolean()
        val compile = Executor { if (stuck.get()) parked += it else it.run() }
        val clock = ManualClock()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, reloadConcurrency = 2)
        testSource(store, options = options, clock = clock, compileExecutor = compile).use { source ->
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
            clock.advance(options.storeReadTimeout)
            val owed = source.tick()
            others.forEach { key -> store.readLog.count { it == key }.assert().isEqualTo(1) }

            clock.advance(Duration.ofSeconds(1))
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
}
