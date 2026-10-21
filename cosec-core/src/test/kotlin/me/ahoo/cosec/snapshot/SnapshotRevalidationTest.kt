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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration

/** Lost and early notifications (F9), resets (F10), revision regressions (F11) and per-tenant fail-closed (F15). */
class SnapshotRevalidationTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Policies, document(1))
        put(APP_A, document(1, "a1"))
        put(APP_B, document(1, "b1"))
    }

    @Test
    fun f9LostNotificationConvergesOnRevalidation() {
        testSource(store).use { source ->
            source.load("app-a").join()
            store.dropNotifications = true
            store.put(SnapshotKey.Policies, document(2))
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)

            source.revalidate().join()

            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun f9NotificationBeforeTheChangeIsReadableConvergesOnPeriodicRevalidation() {
        val options = SnapshotOptions(revalidateInterval = Duration.ofMillis(20))
        SnapshotSource(
            store,
            FakeCompiler(),
            FakeCompiler(),
            options,
            Clock.systemUTC(),
            SnapshotMetrics.NOOP
        ).use { source ->
            source.load().join()
            // The listener runs before the store serves the new value: the reload it starts still reads revision 1.
            store.notifyChanged(SnapshotKey.Policies)
            store.dropNotifications = true
            store.put(SnapshotKey.Policies, document(2))

            await { (source.peek()?.policies as? DocumentState.Present)?.compiled?.revision == 2L }
        }
    }

    @Test
    fun f9NotificationDuringALoadStartsAnotherOnceItCompletes() {
        testSource(store).use { source ->
            store.hold = true
            val cold = source.load()
            store.put(SnapshotKey.Policies, document(2))
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
            store.put(SnapshotKey.Policies, document(2))
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
            store.put(SnapshotKey.Policies, document(2))
            store.reset()
            store.releaseAll()
            store.releaseAll()
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun f11LowerRevisionIsInstalledAndReported() {
        val metrics = RecordingMetrics()
        store.put(SnapshotKey.Policies, document(5))
        testSource(store, metrics = metrics).use { source ->
            source.load().join()

            store.put(SnapshotKey.Policies, document(3))

            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(3)
            metrics.regressions.assert().containsExactly(Triple(SnapshotKey.Policies, 5L, 3L))

            store.put(SnapshotKey.Policies, document(4))
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(4)
            metrics.regressions.assert().hasSize(1)
        }
    }

    @Test
    fun f11SameRevisionWithOtherContentIsInstalledAndReported() {
        val metrics = RecordingMetrics()
        testSource(store, metrics = metrics).use { source ->
            source.load().join()

            store.put(SnapshotKey.Policies, document(1, "rewritten"))

            source.peek()!!.policiesCompiled.entries.assert().containsExactly("rewritten")
            metrics.regressions.assert().containsExactly(Triple(SnapshotKey.Policies, 1L, 1L))
        }
    }

    @Test
    fun f11RegressionIsDetectedAcrossEviction() {
        val metrics = RecordingMetrics()
        store.put(APP_A, document(7, "a7"))
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 1)
        testSource(store, options = options, metrics = metrics).use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            await { source.peek("app-a") == null }
            store.put(APP_A, document(6, "a6"))

            source.load("app-a").join().appCompiled.revision.assert().isEqualTo(6)
            metrics.regressions.assert().containsExactly(Triple(APP_A, 7L, 6L))
        }
    }

    @Test
    fun f15FailClosedTenantFailsOnceLagPassesItsBound() {
        val clock = ManualClock()
        val options = SnapshotOptions(
            revalidateInterval = Duration.ZERO,
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
                revalidateInterval = Duration.ZERO,
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
}
