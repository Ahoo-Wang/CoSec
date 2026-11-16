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
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Clock
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * One eviction pass of `SNP-14`, as an installing thread runs it in place when its install took the family over its
 * cap: at [bound] documents held to their cap (twice the bound, at most `MAX_SLACK` above it), the time of a pass that
 * evicts [batch] of them (`1`, `eighth` of the slack, or the whole `slack`), and of its scan alone (`scan`), which
 * no batch size changes. The source's housekeeping is held, so nothing else evicts.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class EvictionPassBenchmark {
    @Param("1024", "100000")
    var bound: Int = 0

    @Param("1", "eighth", "slack")
    lateinit var batch: String

    private lateinit var source: SnapshotSource<*, *>
    private var limit = 0
    private var cap = 0
    private var next = 0
    private var pool = 0

    @Setup
    fun setup() {
        val slack = minOf(bound, SnapshotFamilies.MAX_SLACK)
        cap = bound + slack
        limit = when (batch) {
            "1" -> 1
            "eighth" -> maxOf(1, slack / 8)
            else -> slack
        }
        pool = cap + slack
        val store = InMemorySnapshotDocumentStore()
        val document = SnapshotDocuments.read("""{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}""".encodeToByteArray())
        (List(pool) { app(it) } + SnapshotKey.Platform).forEach { store.put(it, document) }
        val options = SnapshotOptions(maxApps = bound)
        source = SnapshotSource(
            store, SnapshotKey.Platform, PolicyDocumentCompiler(), DocumentSnapshots.families(PolicyDocumentCompiler(), options),
            options, Clock.systemUTC(), SnapshotMetrics.NOOP, null, null, System::nanoTime, Held(),
        )
        source.load().join()
        fill()
    }

    /** Back to [cap] documents held, with keys not held: the pass before took some out. */
    @Setup(Level.Invocation)
    fun fill() {
        while (source.heldCounts(SnapshotKind.APP).first < cap) {
            source.loadAll(listOf(app(next))).join()
            next = (next + 1) % pool
        }
    }

    @TearDown
    fun tearDown() {
        source.close()
    }

    @Benchmark
    fun pass() = source.evictionPass(SnapshotKind.APP, limit)

    @Benchmark
    fun scan(): Int = source.evictionScan(SnapshotKind.APP, limit)

    private fun app(index: Int) = SnapshotKey.App("app-$index")

    /** Housekeeping that never runs: only the pass under measurement evicts. */
    private class Held : AbstractExecutorService() {
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
