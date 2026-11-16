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
 * round-robin. Eviction runs on housekeeping, asynchronously, and under a burst a family holds more than its bound, up to
 * about the housekeeping queue's 4,096 more; a slice is larger than that, so a document has been evicted before it
 * comes round again: every request is a miss by construction. The source counts its tenant load outcomes and
 * [tearDown] fails when one was anything but a cold load (a hit, a failure, a timeout). It also reports the most
 * tenant documents held during the run, sampled every [SAMPLE_MILLIS] on a thread of its own: how far eviction ran
 * behind the bound.
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
    @Volatile
    private var largestHeld = 0
    private lateinit var sampler: Thread

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
        // One decoded document under every key, so the store stays small however many tenants it holds.
        val document = SnapshotDocuments.read(text.encodeToByteArray())
        (keys + SnapshotKey.Platform).forEach { store.put(it, document) }
        val options = SnapshotOptions(maxTenants = HELD)
        source = DocumentSnapshots.source(store, PolicyDocumentCompiler(), options, Clock.systemUTC(), metrics)
        source.load().join()
        // Every key once, so each is seen: a request reads it from the store of record, as a cold request does.
        val started = System.nanoTime()
        keys.forEach { source.loadAll(listOf(it)).join() }
        println("Setup saw $TENANTS tenants in ${(System.nanoTime() - started) / 1_000_000} ms")
        counting = true
        sampler = Thread(::sample, "cold-load-held-sampler").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The most tenant documents held while the requests run, read every [SAMPLE_MILLIS] on a thread of its own, outside
     * the measured method: how far eviction runs behind the bound.
     */
    private fun sample() {
        while (!Thread.currentThread().isInterrupted) {
            largestHeld = maxOf(largestHeld, held())
            try {
                Thread.sleep(SAMPLE_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun held(): Int = source.entries().count { it.key is SnapshotKey.Tenant }

    @TearDown
    fun tearDown() {
        sampler.interrupt()
        sampler.join()
        source.close()
        val counts = outcomes.mapValues { it.value.get() }
        println("Tenant load outcomes: $counts; the most tenant documents held during the run: $largestHeld (bound $HELD)")
        // A request answered FAILED or TIMEOUT is no cold load either: the row measures only misses that load.
        check(counts.filterKeys { it != LoadOutcome.COLD }.values.all { it == 0L }) {
            "A request was not a cold load: $counts"
        }
    }

    @Benchmark
    fun coldLoad(slice: Slice): SnapshotSet<*, *> = source.loadAll(listOf(slice.next())).join()

    @Benchmark
    @Threads(CONTENDED)
    fun coldLoadContended(slice: Slice): SnapshotSet<*, *> = source.loadAll(listOf(slice.next())).join()

    private companion object {
        /** Each thread's slice is larger than the documents a family can hold beyond its bound, whatever eviction does. */
        const val TENANTS = 65_536
        const val CONTENDED = 8
        const val SLICE = TENANTS / CONTENDED
        const val HELD = 16
        const val SAMPLE_MILLIS = 10L
    }
}
