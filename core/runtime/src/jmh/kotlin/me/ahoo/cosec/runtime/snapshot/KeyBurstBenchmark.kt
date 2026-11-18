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
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import java.security.MessageDigest
import java.time.Clock
import java.util.EnumMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * K's measurement (RFC 0013 B2b step 2b): one invocation is one burst of [BURST] cold requests on one key, offered from
 * [OFFERING] threads, [PER_THREAD] each. The key's store read is held until every request is offered, so each is cold and
 * queues on the one load; then the read is released and every answer awaited, so bursts never overlap.
 *
 * Two kinds of sample, recorded in measurement iterations only and reported at the end of the trial:
 * - **one offer:** the time of one offering call (`loadAll` on the burst key), which returns before the key is answered;
 * - **one join:** a cold request on a key of its own (read at once), made and awaited by one more thread for as long as
 *   the burst is offered: how long another key waits while the burst runs, behind the hand-offs it causes.
 *
 * [slots] is the runner with K events past its own per drain (`k0` … `k64`), so every row comes from the same job. The score, the time of one burst, is secondary: the table is the percentiles.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class KeyBurstBenchmark {
    @Param("k0", "k4", "k16", "k64")
    lateinit var slots: String

    private lateinit var source: SnapshotSource<*, *>
    private val store = BurstStore()
    private val threads = Executors.newFixedThreadPool(OFFERING + 1) { Thread(it, "key-burst").apply { isDaemon = true } }
    private var bursts = 0
    private var seconds = 0L
    private var recording = false
    private val offers = Samples(MAX_BURSTS * BURST)
    private val joins = Samples(MAX_JOINS)
    private val outcomes = EnumMap<LoadOutcome, AtomicLong>(LoadOutcome::class.java).apply {
        LoadOutcome.entries.forEach { put(it, AtomicLong()) }
    }

    private val metrics = object : SnapshotMetrics {
        override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
            if (kind == SnapshotKind.TENANT) outcomes.getValue(outcome).incrementAndGet()
        }
    }

    @Setup
    fun setup() {
        val compiler = PolicyDocumentCompiler()
        val options = SnapshotOptions(maxTenants = HELD)
        source = SnapshotSource(
            store, SnapshotKey.Platform, compiler, DocumentSnapshots.families(compiler, options),
            options, Clock.systemUTC(), metrics, null, null, System::nanoTime, null, factory(slots),
        )
        source.load().join()
    }

    @Setup(Level.Iteration)
    fun iteration(params: IterationParams) {
        recording = params.type == IterationType.MEASUREMENT
    }

    @Benchmark
    fun burst() {
        val key = SnapshotKey.Tenant("burst-${bursts++}")
        // What makes the burst a cold load: its key is not installed when it begins, and one load of it runs.
        check(source.peekAll(listOf(key)) == null) { "Burst key [$key] was installed when its burst began." }
        val start = CyclicBarrier(OFFERING + 1)
        val offered = CountDownLatch(OFFERING)
        val answers = arrayOfNulls<CompletableFuture<*>>(BURST)
        val times = LongArray(BURST)
        repeat(OFFERING) { slice ->
            threads.execute {
                start.await()
                for (i in slice * PER_THREAD until (slice + 1) * PER_THREAD) {
                    val began = System.nanoTime()
                    answers[i] = source.loadAll(listOf(key))
                    times[i] = System.nanoTime() - began
                }
                offered.countDown()
            }
        }
        val second = threads.submit {
            start.await()
            while (offered.count > 0) {
                val began = System.nanoTime()
                source.loadAll(listOf(SnapshotKey.Tenant("second-${seconds++}"))).join()
                if (recording) joins.add(System.nanoTime() - began)
            }
        }
        offered.await()
        second.get()
        store.release(key)
        answers.forEach { it!!.join() }
        val loads = store.coldReads(key)
        check(loads == 1) { "Burst key [$key] was loaded cold $loads times in its burst, not once." }
        if (recording) times.forEach(offers::add)
    }

    @TearDown
    fun tearDown() {
        threads.shutdownNow()
        store.releaseAll()
        source.close()
        val counts = outcomes.mapValues { it.value.get() }
        println("\n[$slots] one offer: ${offers.report()}")
        println("[$slots] one join, a second key during the burst: ${joins.report()}")
        println("[$slots] bursts: $bursts, second keys: $seconds, tenant load outcomes: $counts")
        // A burst request answered FAILED or TIMEOUT measured no cold load: the trial is void. One served the burst's
        // load's install counts as HIT: it was queued when the load ended, and taken after (the runner's load lane).
        check(counts.filterKeys { it == LoadOutcome.FAILED || it == LoadOutcome.TIMEOUT }.values.all { it == 0L }) {
            "A request was not answered by its burst's load: $counts"
        }
    }

    /** Nanosecond samples, reported in microseconds. */
    private class Samples(capacity: Int) {
        private val values = LongArray(capacity)
        private var size = 0

        @Synchronized
        fun add(nanos: Long) {
            if (size < values.size) values[size++] = nanos
        }

        @Synchronized
        fun report(): String {
            if (size == 0) return "no samples"
            val sorted = values.copyOf(size).apply { sort() }
            fun at(q: Double) = "%.1f".format(sorted[minOf(size - 1, (q * size).toInt())] / 1_000.0)
            return "n=$size, p50=${at(0.5)} µs, p99=${at(0.99)} µs, p99.9=${at(0.999)} µs, max=${at(1.0)} µs"
        }
    }

    /** One decoded document under every key; a burst key's read held until [release], any other read at once. */
    private class BurstStore : SnapshotDocumentStore {
        private val present = SnapshotRead.Present(
            SnapshotDocuments.read(TEXT.encodeToByteArray()),
            SnapshotDigest(MessageDigest.getInstance(SnapshotDigest.SHA_256).digest(TEXT.encodeToByteArray())),
        )
        private val held = ConcurrentHashMap<SnapshotKey, CompletableFuture<SnapshotRead>>()
        private val cold = ConcurrentHashMap<SnapshotKey, AtomicInteger>()

        /** A key never seen is loaded with a full read, then confirmed with a conditional one: the full reads count. */
        override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> {
            if (burst(key)) cold.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            return answer(key)
        }

        override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> = answer(key)

        private fun answer(key: SnapshotKey): CompletableFuture<SnapshotRead> =
            if (burst(key)) held.computeIfAbsent(key) { CompletableFuture() } else CompletableFuture.completedFuture(present)

        private fun burst(key: SnapshotKey) = key is SnapshotKey.Tenant && key.tenantId.startsWith("burst-")

        fun coldReads(key: SnapshotKey): Int = cold.remove(key)?.get() ?: 0

        override fun subscribe(listener: SnapshotChangeListener): AutoCloseable = AutoCloseable {}

        fun release(key: SnapshotKey) {
            held.computeIfAbsent(key) { CompletableFuture() }.complete(present)
            held.remove(key)
        }

        fun releaseAll() = held.keys.forEach(::release)
    }

    /** Runners draining [k] events past their own. */
    private class KRunners(private val k: Int) : SlotFactory {
        override fun <T : Any> create(key: SnapshotKey, compiler: SnapshotCompiler<T>, context: SlotContext): KeySlot<T> =
            KeyRunner(key, compiler, context, k)
    }

    private companion object {
        const val OFFERING = 8
        const val PER_THREAD = 1_250
        const val BURST = OFFERING * PER_THREAD
        const val HELD = 64
        /** Room for this many measured bursts' offers, and as many joins: more than any run here takes. */
        const val MAX_BURSTS = 200
        const val MAX_JOINS = 1_000_000
        const val TEXT = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""

        fun factory(name: String): SlotFactory = KRunners(name.removePrefix("k").toInt())
    }
}
