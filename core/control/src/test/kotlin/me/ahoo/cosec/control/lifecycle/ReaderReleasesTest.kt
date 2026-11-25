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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * `CTL-89`: `cosec.control.instances.releases` follows the readers of the store on every control host, at each shadow
 * pass, whether or not that host holds the pass's lease, and without anyone asking `GET /instances`.
 */
class ReaderReleasesTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val written = InMemoryShadowReports(clock)

    /** A replica that never gets the shadow lease: another holds it. */
    private val leaseless = object : ControlStore by memory {
        override suspend fun acquireLease(name: String, holder: String, duration: Duration): Boolean = false
    }

    private fun changes(store: ControlStore): Changes {
        val ids = AtomicInteger()
        return Changes(
            store,
            Revisions(store, clock),
            { ChangeId("c${ids.incrementAndGet()}") },
            PERMIT_ALL,
            clock,
            ChangeOptions(),
            revocations = RevocationReader.NONE,
            lockout = LockoutCheck.UNCHECKED,
            reports = written,
        )
    }

    @Test
    fun `every control host's release count follows the readers at each shadow pass, lease or not - CTL-89`() =
        runBlocking<Unit> {
            written.write(InstanceStatus("gw-1", clock.instant(), release = "8.0.0-beta.9"))
            written.write(InstanceStatus("gw-2", clock.instant(), release = "8.0.0-beta.8"))
            val holder = changes(memory)
            val other = changes(leaseless)
            val passes = listOf(
                ControlMaintenance(memory, Revisions(memory, clock), holder, PASS, clock),
                ControlMaintenance(leaseless, Revisions(leaseless, clock), other, PASS, clock),
            )
            listOf(holder, other).forEach { it.readerReleases.assert().describedAs("before any pass").isEqualTo(0) }
            passes.forEach { it.closeShadowWindows() }
            listOf(holder, other).forEach { it.readerReleases.assert().isEqualTo(2) }
            // gw-2 is upgraded: the next pass past the read's age says one release.
            written.write(InstanceStatus("gw-2", clock.instant(), release = "8.0.0-beta.9"))
            clock.now = clock.now.plus(InstanceStatuses.MAX_AGE).plusMillis(1)
            passes.forEach { it.closeShadowWindows() }
            listOf(holder, other).forEach { it.readerReleases.assert().isEqualTo(1) }
        }

    private companion object {
        val PASS: Duration = Duration.ofSeconds(5)
    }
}
