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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.ChangePage
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shadow pass over `state:SHADOWING` (R2C-3): one change it cannot read is skipped and counted, never the end of
 * the pass, and the windows it looked at longest ago come first, so a pass its time cuts short reaches the rest on the
 * next one.
 */
class ShadowPassTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val written = InMemoryShadowReports(clock)

    /** The windows looked at, in order; each look takes [lookTakes]. */
    private val looks = CopyOnWriteArrayList<String>()
    private var lookTakes = Duration.ZERO

    private val reader = object : ShadowReportReader {
        override suspend fun reports(window: String): List<ShadowReport> {
            looks += window
            if (!lookTakes.isZero) delay(lookTakes.toMillis())
            return written.reports(window)
        }

        override suspend fun statuses(): List<InstanceStatus> = emptyList()
    }

    private fun changes(store: ControlStore, revisions: Revisions, shadow: ShadowOptions): Changes {
        val counter = AtomicInteger()
        return Changes(
            store,
            revisions,
            { ChangeId("c${counter.incrementAndGet()}") },
            PERMIT_ALL,
            clock,
            ChangeOptions(shadow = shadow),
            revocations = RevocationReader.NONE,
            lockout = LockoutCheck.UNCHECKED,
            reports = reader,
        )
    }

    private suspend fun staged(changes: Changes, revisions: Revisions, tenants: List<String>): List<ChangeRequest> =
        tenants.map { tenant ->
            val key = SnapshotKey.Tenant(tenant)
            revisions.publish(key, null, StoreFixtures.candidate(key), StoreFixtures.publication())
            val proposed = changes.propose(PLATO, key, 1, listOf(Operation.PutPolicy(allow("p-$tenant"))), tenant)
            changes.stage(PLATO, proposed.id).also { clock.now = clock.now.plusSeconds(1) }
        }

    @Test
    fun `a pass cut short says how far it got, and the next one starts with the windows behind the cut`() =
        runBlocking<Unit> {
            memory.keepOpenChanges()
            val revisions = Revisions(memory, clock)
            val changes = changes(memory, revisions, OPEN)
            val windows = staged(changes, revisions, TENANTS).map { it.shadow!!.window }
            lookTakes = Duration.ofMillis(LOOK_MILLIS)
            val maintenance = ControlMaintenance(memory, revisions, changes, Duration.ofMillis(PASS_MILLIS), clock)
            maintenance.closeShadowWindows()
            val first = looks.toList()
            first.size.assert().describedAs("the pass was cut").isLessThan(windows.size)
            val behind = windows - first.toSet()
            looks.clear()
            clock.now = clock.now.plusSeconds(1)
            maintenance.closeShadowWindows()
            val second = looks.toList()
            second.assert().isNotEmpty()
            second.take(behind.size).forEach { it.assert().describedAs("behind the first cut").isIn(behind) }
            // Once every window was looked at, the one looked at longest ago comes first.
            lookTakes = Duration.ZERO
            looks.clear()
            clock.now = clock.now.plusSeconds(1)
            maintenance.closeShadowWindows()
            looks.toSet().assert().isEqualTo(windows.toSet())
            looks.first().assert().isIn(behind - second.toSet() + first.toSet())
        }

    @Test
    fun `a shadowing change that does not read is skipped, the others are looked at, and the staged list keeps it`() =
        runBlocking<Unit> {
            memory.keepOpenChanges()
            memory.keepStagedList()
            val revisions = Revisions(memory, clock)
            val plain = changes(memory, revisions, IMMEDIATE)
            val (broken, fine) = staged(plain, revisions, listOf("acme", "globex"))
            val store = object : ControlStore by memory {
                override suspend fun changes(
                    state: ChangeState,
                    after: String?,
                    limit: Int,
                    documents: (SnapshotKey) -> Boolean,
                ): ChangePage {
                    val page = memory.changes(state, after, limit, documents)
                    return ChangePage(
                        page.changes.map {
                            if (it.id == broken.id) ListedChange.Unreadable(it.id, it.document) else it
                        },
                        page.next,
                    )
                }
            }
            val maintenance = ControlMaintenance(store, revisions, changes(store, revisions, IMMEDIATE), PASS, clock)
            maintenance.closeShadowWindows().map { it.id }.assert().containsExactly(fine.id)
            memory.staged()?.windows?.keys.assert().describedAs("not reconciled on an incomplete read")
                .contains(broken.key)
            plain.get(broken.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        }

    private companion object {
        val TENANTS = (1..6).map { "t$it" }
        const val LOOK_MILLIS = 150L
        const val PASS_MILLIS = 400L
        val PASS: Duration = Duration.ofMinutes(1)
        val OPEN = ShadowOptions(Duration.ofHours(1), 1_000, Duration.ofHours(24))
        val IMMEDIATE = ShadowOptions(Duration.ZERO, 0, Duration.ZERO)
    }
}
