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
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.InstalledDocument
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportReader
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * `CTL-62` (R2C-12): a revision's propagation counts, of the instances that reported in the last minute, those that
 * hold the document and those of them that installed the revision, so a tenant held by three of fifty replicas reads
 * `installed: 3, holding: 3`, not three of fifty. The statuses are read at most every five seconds and the answer says
 * when; a read that fails is never kept, and never answered from an older one.
 */
class PropagationTest {
    private val clock = MutableClock()
    private val written = InMemoryShadowReports(clock)
    private val reads = AtomicInteger()

    /** Fails each read while positive, as a store that does not answer. */
    private val failures = AtomicInteger()

    private val reader = object : ShadowReportReader {
        override suspend fun reports(window: String): List<ShadowReport> = emptyList()

        override suspend fun statuses(): List<InstanceStatus> {
            reads.incrementAndGet()
            if (failures.getAndDecrement() > 0) throw IllegalStateException("timeout")
            return written.statuses()
        }
    }
    private val windows = ShadowWindows(reader, clock = clock)

    private suspend fun status(instance: String, vararg documents: Pair<SnapshotKey, Long>) = written.write(
        InstanceStatus(instance, clock.instant(), documents.associate { (key, revision) -> key to InstalledDocument(revision, Duration.ZERO) }),
    )

    @Test
    fun `propagation counts the instances that hold the document, and says when it read them - CTL-62`() = runBlocking<Unit> {
        status("i1", T1 to 2, SnapshotKey.Platform to 5)
        status("i2", T1 to 1, SnapshotKey.Platform to 5)
        status("i3", SnapshotKey.Platform to 5)
        windows.propagation(T1, 2).assert().isEqualTo(Propagation(2, 1, 2, 3, clock.now))
        // The platform document is held by every instance: holding is every recent instance.
        windows.propagation(SnapshotKey.Platform, 5).assert().isEqualTo(Propagation(5, 3, 3, 3, clock.now))
        // A tenant no instance holds is not propagated yet, whatever the instances that do not hold it report.
        windows.propagation(SnapshotKey.Tenant("t2"), 1).assert().isEqualTo(Propagation(1, 0, 0, 3, clock.now))
    }

    @Test
    fun `the statuses are read at most every five seconds, and the answer is as old as that read`() = runBlocking<Unit> {
        status("i1", T1 to 1)
        val first = windows.propagation(T1, 2)
        status("i1", T1 to 2)
        clock.now = clock.now.plus(InstanceStatuses.MAX_AGE).minusMillis(1)
        windows.propagation(T1, 2).assert().isEqualTo(first)
        reads.get().assert().isEqualTo(1)
        clock.now = clock.now.plusMillis(1)
        windows.propagation(T1, 2).assert().isEqualTo(Propagation(2, 1, 1, 1, clock.now))
        reads.get().assert().isEqualTo(2)
    }

    @Test
    fun `a status read that fails is never kept nor answered from an older read - CTL-62`() = runBlocking<Unit> {
        status("i1", T1 to 1)
        windows.propagation(T1, 1).installed.assert().isEqualTo(1)
        status("i1", T1 to 2)
        clock.now = clock.now.plus(InstanceStatuses.MAX_AGE)
        failures.set(1)
        // An outage is 503 (CTL-52), never the answer read five seconds ago.
        runCatching { windows.propagation(T1, 2) }.exceptionOrNull().assert().isInstanceOf(ControlStoreException::class.java)
        // The next call reads again, at once.
        windows.propagation(T1, 2).assert().isEqualTo(Propagation(2, 1, 1, 1, clock.now))
        reads.get().assert().isEqualTo(3)
    }

    private companion object {
        val T1 = SnapshotKey.Tenant("t1")
    }
}
