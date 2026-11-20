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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * `SnapshotSource.peek` finds a family by an array read, so a warm peek of an app allocates the `Snapshot` it returns
 * and nothing else, whether or not the JIT's escape analysis would have removed an iterator (#1684 saw a fork allocate
 * it).
 *
 * Why it is not flaky: the source and its class are this test's own, and the calls are measured from the first on, so
 * they run interpreted and in C1 code, which eliminates no allocation. The bound, 48 B per call, is twice the 24 B the
 * snapshot takes (32 B without compressed pointers). Code with the iterator measures 56 B per call. A JIT that had
 * already compiled `peek` with escape analysis could only allocate less, so another test warming it first never makes
 * this one fail; it can only hide a regression, which the `-XX:-DoEscapeAnalysis` benchmark run shows instead.
 */
class PeekAllocationTest {
    @Test
    fun `a warm peek of an app allocates no more than the snapshot it returns`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val store = ControllableStore().apply {
            put(SnapshotKey.Platform, document(1))
            put(APP_A, document(1))
        }
        val compiler = FakeCompiler()
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS)
        val ticker = AtomicLong()
        // The app family second, so a search by kind would step past the tenant family to reach it.
        val families = listOf(
            SnapshotFamily(SnapshotKind.TENANT, compiler, options.maxTenants, options.maxAbsentTenants),
            SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsentApps),
        )
        SnapshotSource(
            store, SnapshotKey.Platform, compiler, families, options, ManualClock(), SnapshotMetrics.NOOP,
            directExecutor, null, { ticker.incrementAndGet() },
        ).use { source ->
            source.load(APP_A.appId).join()
            source.peek(APP_A.appId).assert().isNotNull()
            val thread = Thread.currentThread().threadId()
            var found = 0
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(CALLS) { if (source.peek(APP_A.appId) != null) found++ }
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            found.assert().isEqualTo(CALLS)
            // The snapshot is 24 bytes (32 without compressed pointers); an iterator per call would add 32 more.
            allocated.assert().describedAs("$allocated bytes over $CALLS peeks").isLessThan(CALLS.toLong() * BOUND)
        }
    }

    private companion object {
        const val CALLS = 5_000
        const val BOUND = 48L
    }
}
