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
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import java.time.Clock
import java.util.EnumMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The snapshot source's cold path (RFC 0013 B2b, gate 4): a request for a tenant document that is not installed, read
 * from an in-memory store, compiled on the source's own compile pool, installed and answered. The platform document is
 * installed once, so each request's cost is the tenant's: the thread hops (the compile pool and back), the store's read,
 * the compilation of an empty document, the install and the eviction it causes.
 *
 * The store holds [TENANTS] tenant documents and the source [HELD]. Each thread requests its own slice of [SLICE] keys
 * round-robin, so a document has been evicted long before it comes round again: every request is a miss by
 * construction. Eviction runs on housekeeping, asynchronously; if it ever lagged, the row would measure hits among the
 * misses, so the source counts its tenant load outcomes and [tearDown] fails when one was a hit.
 *
 * [coldLoad] is one thread; [coldLoadContended] is [CONTENDED] threads, each on its own slice, contending for the compile
 * pool and the eviction. The "before" of B2b's rewrite, on today's slot.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class SnapshotColdLoadBenchmark {
    private lateinit var source: SnapshotSource<*, *>
    private val outcomes = EnumMap<LoadOutcome, AtomicLong>(LoadOutcome::class.java).apply {
        LoadOutcome.entries.forEach { put(it, AtomicLong()) }
    }
    private val slices = AtomicInteger()
    @Volatile
    private var counting = false

    /** The tenant load outcomes, once setup is done: every request must be a miss. */
    private val metrics = object : SnapshotMetrics {
        override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
            if (counting && kind == SnapshotKind.TENANT) outcomes.getValue(outcome).incrementAndGet()
        }
    }

    /** One thread's keys, [SLICE] of them, requested round-robin. */
    @State(org.openjdk.jmh.annotations.Scope.Thread)
    open class Slice {
        lateinit var keys: List<SnapshotKey>
        private var next = 0

        @Setup
        fun setup(benchmark: SnapshotColdLoadBenchmark) {
            val slice = benchmark.slices.getAndIncrement() % (TENANTS / SLICE)
            keys = List(SLICE) { SnapshotKey.Tenant("tenant-${slice * SLICE + it}") }
        }

        fun next(): SnapshotKey = keys[next].also { next = (next + 1) % SLICE }
    }

    @Setup
    fun setup() {
        val store = InMemorySnapshotDocumentStore()
        val text = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
        val keys = List(TENANTS) { SnapshotKey.Tenant("tenant-$it") }
        (keys + SnapshotKey.Platform).forEach { store.put(it, SnapshotDocuments.read(text.encodeToByteArray())) }
        val options = SnapshotOptions(maxTenants = HELD)
        source = DocumentSnapshots.source(store, PolicyDocumentCompiler(), options, Clock.systemUTC(), metrics)
        source.load().join()
        // Every key once, so each is seen: a request reads it from the store of record, as a cold request does.
        keys.forEach { source.loadAll(listOf(it)).join() }
        counting = true
    }

    @TearDown
    fun tearDown() {
        println("Tenant entries installed at the end: ${source.entries().count { it.key is SnapshotKey.Tenant }}")
        source.close()
        val counts = outcomes.mapValues { it.value.get() }
        println("Tenant load outcomes: $counts")
        check(counts.getValue(LoadOutcome.HIT) == 0L) { "A request was a hit, not a miss: $counts" }
    }

    @Benchmark
    fun coldLoad(slice: Slice): SnapshotSet<*, *> = source.loadAll(listOf(slice.next())).join()

    @Benchmark
    @Threads(CONTENDED)
    fun coldLoadContended(slice: Slice): SnapshotSet<*, *> = source.loadAll(listOf(slice.next())).join()

    private companion object {
        const val TENANTS = 4096
        const val CONTENDED = 8
        const val SLICE = TENANTS / CONTENDED
        const val HELD = 16
    }
}
