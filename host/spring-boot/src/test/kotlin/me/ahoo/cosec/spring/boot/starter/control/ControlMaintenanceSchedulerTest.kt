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

package me.ahoo.cosec.spring.boot.starter.control

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlMaintenance
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ControlMaintenanceSchedulerTest {
    private companion object {
        val PUBLICATION = Publication("c1", ProvenanceKind.PUBLISH, Actor(TenantId.PLATFORM, PrincipalId("plato")))
    }

    @Test
    fun `both passes run at their interval, and a failed pass does not stop the next`() {
        val reclaims = AtomicInteger()
        val shadows = AtomicInteger()
        val maintenance = mockk<ControlMaintenance> {
            coEvery { reconcileApps() } returns emptySet()
            coEvery { reclaim() } answers {
                if (reclaims.incrementAndGet() == 1) error("Redis is down")
                emptyMap()
            }
            coEvery { closeShadowWindows() } answers {
                shadows.incrementAndGet()
                emptyList()
            }
        }
        val scheduler = ControlMaintenanceScheduler(maintenance, Duration.ofMillis(20), Duration.ofMillis(20))
        scheduler.start()
        scheduler.isRunning.assert().isTrue()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while ((reclaims.get() < 3 || shadows.get() < 3) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        scheduler.stop()
        scheduler.isRunning.assert().isFalse()
        reclaims.get().assert().isGreaterThanOrEqualTo(3)
        shadows.get().assert().isGreaterThanOrEqualTo(3)
    }

    /** Coordinator review: a pass's own timeout, or an Error, must not end the loop silently. */
    @Test
    fun `a pass that times out or throws an error does not stop the next`() {
        val timeout: Throwable = try {
            runBlocking { withTimeout(1) { delay(1_000) } }
            error("unreachable")
        } catch (e: TimeoutCancellationException) {
            e
        }
        val shadows = AtomicInteger()
        val maintenance = mockk<ControlMaintenance> {
            coEvery { reconcileApps() } returns emptySet()
            coEvery { reclaim() } returns emptyMap()
            coEvery { closeShadowWindows() } answers {
                when (shadows.incrementAndGet()) {
                    1 -> throw timeout
                    2 -> throw AssertionError("an error")
                    else -> emptyList()
                }
            }
        }
        val scheduler = ControlMaintenanceScheduler(maintenance, Duration.ofHours(1), Duration.ofMillis(20))
        scheduler.start()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (shadows.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        scheduler.stop()
        shadows.get().assert().isGreaterThanOrEqualTo(3)
    }

    /**
     * Review of #1181 (F1): after a downgrade, an older writer published an app without listing it. The list still says
     * complete; it is not trusted from start, not from the first reclamation pass an hour later.
     */
    @Test
    fun `the app list is reconciled at start, before the first interval`() = runBlocking<Unit> {
        val store = InMemoryControlStore()
        val revisions = Revisions(store)
        revisions.publish(SnapshotKey.Platform, null, document(SnapshotKey.Platform), PUBLICATION)
        val older = object : ControlStore by store {
            override suspend fun apps(): Versioned<AppList>? = null

            override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean = true
        }
        val legacy = SnapshotKey.App("legacy")
        Revisions(older).publish(legacy, null, document(legacy), PUBLICATION)
        store.apps()!!.value.complete.assert().isTrue()

        val changes = Changes(store, revisions, { ChangeId("unused") }, { _, _, _ -> ControlDecision.PERMIT })
        val maintenance = ControlMaintenance(store, revisions, changes, Duration.ofHours(1))
        val scheduler = ControlMaintenanceScheduler(maintenance, Duration.ofHours(1), Duration.ofHours(1))
        scheduler.start()
        try {
            withTimeout(10_000) {
                while (store.apps()!!.value.complete) delay(10)
            }
        } finally {
            scheduler.stop()
        }
        store.apps()!!.value.assert().isEqualTo(AppList(emptySet(), complete = false))
    }

    private fun document(key: SnapshotKey): PolicyDocument {
        val (kind, owner) = Revisions.ownerOf(key)
        return PolicyDocument(kind, owner, 1, Instant.EPOCH, emptyList(), emptyList())
    }

    @Test
    fun `intervals must be positive`() {
        val maintenance = mockk<ControlMaintenance>()
        assertThrows<IllegalArgumentException> {
            ControlMaintenanceScheduler(maintenance, Duration.ZERO, Duration.ofMinutes(1))
        }
        assertThrows<IllegalArgumentException> {
            ControlMaintenanceScheduler(maintenance, Duration.ofHours(1), Duration.ZERO)
        }
    }
}
