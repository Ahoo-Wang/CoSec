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
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The absent kind's eviction scan (RFC 0013 §5.4, the absent kind's member set), warmed: a family holding [documents]
 * documents and `max-absent` absent entries, and the scan an absent entry's install over its cap runs, choosing one
 * victim. It walks the absent entries alone, so it must cost the same at either [documents]. Apart from
 * [EvictionPassBenchmark], whose family holds no absent entry, so that its rows compare with the runs before it.
 * Housekeeping never runs.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class AbsentScanBenchmark {
    @Param("1024", "100000")
    var documents: Int = 0

    private lateinit var source: SnapshotSource<*, *>

    @Setup
    fun setup() {
        val store = InMemorySnapshotDocumentStore()
        val document = SnapshotDocuments.read("""{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}""".encodeToByteArray())
        (List(documents) { SnapshotKey.App("app-$it") } + SnapshotKey.Platform).forEach { store.put(it, document) }
        val options = SnapshotOptions(maxApps = documents)
        source = SnapshotSource(
            store, SnapshotKey.Platform, PolicyDocumentCompiler(), DocumentSnapshots.families(PolicyDocumentCompiler(), options),
            options, Clock.systemUTC(), SnapshotMetrics.NOOP, null, null, System::nanoTime, Held(),
        )
        source.load().join()
        (0 until documents).forEach { source.loadAll(listOf(SnapshotKey.App("app-$it"))).join() }
        (0 until options.maxAbsentApps).forEach { source.loadAll(listOf(SnapshotKey.App("absent-$it"))).join() }
    }

    @TearDown
    fun tearDown() {
        source.close()
    }

    @Benchmark
    fun scanAbsent(): Int = source.evictionScan(SnapshotKind.APP, 1, absent = true)

    /** Housekeeping that never runs: nothing evicts during the measurement. */
    private class Held : java.util.concurrent.AbstractExecutorService(), ExecutorService {
        private var shutdown = false

        override fun execute(command: Runnable) = Unit

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shutdown = true }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }
}
