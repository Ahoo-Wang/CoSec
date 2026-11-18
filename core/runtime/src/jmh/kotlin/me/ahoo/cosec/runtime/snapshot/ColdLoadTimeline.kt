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
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.time.Clock
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A timeline of uncontended cold loads of tenant documents, as `SnapshotColdLoadBenchmark.coldLoad` makes them, stamped
 * from outside the source (RFC 0013 §5.4, the cold-path criterion). The store, the compiler, the compile pool,
 * housekeeping, the scheduler and the metrics are wrapped; nothing in the source is changed. The pool and housekeeping
 * are real executors, made with the source's own thread markers. Each stamp carries the load it belongs to (a pool or
 * housekeeping task, the load that submitted it), so a load's tail is not counted against the next one. The loads run
 * back to back, as in the benchmark.
 *
 * The criterion's two windows, per load: call → compile task submitted (the requester's own work before the pool
 * can start) and compilation ended → answered (the pool's work before the waiter can wake). Both sides carry the same
 * stamps in the first; in the second, `Slot` carries one more (its hand to housekeeping comes before its answer).
 */
internal class ColdLoadTimeline(private val runner: Boolean, private val loads: Int, private val warmup: Int) {
    private class Stamp(val load: Int, val name: String, val nanos: Long, val thread: String)

    /** A run's table, and the medians of its two windows in nanoseconds. */
    class Result(val report: String, val window1: Long, val window2: Long)

    private val stamps = ArrayList<Stamp>(loads * STAMPS_PER_LOAD)

    @Volatile
    private var current = -1

    @Volatile
    private var recording = false
    private val carried = ThreadLocal<Int?>()

    private fun stamp(name: String, load: Int = carried.get() ?: current) {
        if (!recording || load < 0) return
        val stamp = Stamp(load, name, System.nanoTime(), group(Thread.currentThread().name))
        synchronized(stamps) { stamps += stamp }
    }

    /** An executor stamping submission, start and end, over a real one made once the source's markers are known. */
    private inner class Stamped(private val label: String) : AbstractExecutorService() {
        @Volatile
        lateinit var real: ExecutorService

        override fun execute(command: Runnable) {
            val load = carried.get() ?: current
            stamp("$label submitted", load)
            real.execute {
                carried.set(load)
                stamp("$label started", load)
                try {
                    command.run()
                } finally {
                    stamp("$label ended", load)
                    carried.remove()
                }
            }
        }

        override fun shutdown() = real.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = real.shutdownNow()

        override fun isShutdown(): Boolean = real.isShutdown

        override fun isTerminated(): Boolean = real.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = real.awaitTermination(timeout, unit)
    }

    private fun store(keys: List<SnapshotKey>): SnapshotDocumentStore {
        val inMemory = InMemorySnapshotDocumentStore()
        val document = SnapshotDocuments.read(
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}""".encodeToByteArray()
        )
        (keys + SnapshotKey.Platform).forEach { inMemory.put(it, document) }
        fun <R> stamped(key: SnapshotKey, read: CompletableFuture<R>): CompletableFuture<R> {
            if (key is SnapshotKey.Tenant) read.whenComplete { _, _ -> stamp("read answered") }
            return read
        }
        return object : SnapshotDocumentStore by inMemory {
            override fun read(key: SnapshotKey): CompletableFuture<me.ahoo.cosec.spi.snapshot.SnapshotRead> {
                if (key is SnapshotKey.Tenant) stamp("read started")
                return stamped(key, inMemory.read(key))
            }

            override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?):
                CompletableFuture<me.ahoo.cosec.spi.snapshot.SnapshotRead> {
                if (key is SnapshotKey.Tenant) stamp("read started")
                return stamped(key, inMemory.readIfChanged(key, known))
            }
        }
    }

    private fun scheduler(real: ScheduledExecutorService) = object : ScheduledExecutorService by real {
        private fun <V> stamped(future: ScheduledFuture<V>): ScheduledFuture<V> {
            stamp("timer scheduled")
            return object : ScheduledFuture<V> by future {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                    stamp("timer cancelled")
                    return future.cancel(mayInterruptIfRunning)
                }
            }
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            stamped(real.schedule(command, delay, unit))

        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            stamped(real.schedule(callable, delay, unit))
    }

    private val metrics = object : SnapshotMetrics {
        override fun onInstall(entry: SnapshotEntry<*>) {
            if (entry.key is SnapshotKey.Tenant) stamp(INSTALLED)
        }

        override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
            if (kind == SnapshotKind.TENANT) stamp(ANSWERED)
        }
    }

    /** The loads, then their timeline. */
    fun run(): Result {
        val keys = List(TENANTS) { SnapshotKey.Tenant("tenant-$it") }
        val policies = PolicyDocumentCompiler()
        val compiler = SnapshotCompiler { key, document ->
            if (key is SnapshotKey.Tenant) stamp("compilation started")
            policies.compile(key, document).also { if (key is SnapshotKey.Tenant) stamp(COMPILED) }
        }
        val options = SnapshotOptions(maxTenants = HELD)
        val pool = Stamped("pool task")
        val housekeeping = Stamped("housekeeping task")
        val realScheduler = SnapshotExecutors.newScheduler(ThreadLocal())
        var threads: SnapshotThreads? = null
        val slots = object : SlotFactory {
            override fun <T : Any> create(
                key: SnapshotKey,
                compiler: SnapshotCompiler<T>,
                context: SlotContext,
            ): KeySlot<T> {
                if (threads == null) {
                    threads = context.threads
                    pool.real = SnapshotExecutors.newCompileExecutor(
                        options,
                        context.threads.onOwnThread,
                        context.threads.drainsUnbounded
                    )
                    housekeeping.real =
                        SnapshotExecutors.newHousekeeping(context.threads.onOwnThread, context.threads.drainsUnbounded)
                }
                return if (runner) KeyRunner(key, compiler, context) else SlotFactory.SLOT.create(key, compiler, context)
            }
        }
        val source = SnapshotSource(
            store(keys), SnapshotKey.Platform, compiler, DocumentSnapshots.families(compiler, options), options,
            Clock.systemUTC(), metrics, pool, scheduler(realScheduler), System::nanoTime, housekeeping, slots,
        )
        val times = LongArray(loads)
        try {
            source.load().join()
            keys.forEach { source.loadAll(listOf(it)).join() }
            var next = 0
            repeat(warmup) { source.loadAll(listOf(keys[next++ % TENANTS])).join() }
            recording = true
            for (i in 0 until loads) {
                current = i
                val begin = System.nanoTime()
                stamp(CALL)
                val future = source.loadAll(listOf(keys[next++ % TENANTS]))
                stamp("request returned")
                future.whenComplete { _, _ -> stamp("future completed") }
                future.join()
                stamp("running after join")
                times[i] = System.nanoTime() - begin
            }
            Thread.sleep(SETTLE_MILLIS)
            recording = false
        } finally {
            source.close()
            pool.real.shutdownNow()
            housekeeping.real.shutdownNow()
            realScheduler.shutdownNow()
        }
        return report(times)
    }

    private fun report(times: LongArray): Result {
        val firsts = stamps.groupBy { it.load }.values.map { list ->
            list.sortedBy { it.nanos }.distinctBy { it.name }.associateBy { it.name }
        }
        fun window(from: String, to: String) =
            firsts.mapNotNull { s -> s[to]?.let { t -> s[from]?.let { f -> t.nanos - f.nanos } } }
        val name = if (runner) "runner" else "Slot"
        val window1 = percentile(window(CALL, SUBMITTED), MEDIAN)
        val window2 = percentile(window(COMPILED, ANSWERED), MEDIAN)
        val report = buildString {
            appendLine(
                "== $name: $loads cold loads, call to running after join: " +
                    "median ${micros(percentile(times.toList(), MEDIAN))} µs, p90 ${micros(percentile(times.toList(), P90))} µs"
            )
            appendLine("window 1 (call → pool task submitted), median µs: ${micros(window1)}")
            appendLine("window 2 (compilation ended → answered), median µs: ${micros(window2)}")
            appendLine("| stamp | loads | median µs | p90 µs | thread |")
            appendLine("|---|---|---|---|---|")
            val offsets = firsts.flatMap { s -> s.values.map { it.name to (it.nanos - (s[CALL]?.nanos ?: it.nanos)) } }
                .groupBy({ it.first }, { it.second })
            val threadsOf = firsts.flatMap { it.values }.groupBy({ it.name }, { it.thread })
            for ((stamp, values) in offsets.entries.sortedBy { percentile(it.value, MEDIAN) }) {
                val where = threadsOf.getValue(stamp).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} ${PERCENT * it.value / values.size}%" }
                appendLine(
                    "| $stamp | ${values.size} | ${micros(percentile(values, MEDIAN))} | " +
                        "${micros(percentile(values, P90))} | $where |"
                )
            }
        }
        return Result(report, window1, window2)
    }

    companion object {
        const val REQUESTER = "cold-requester"
        private const val TENANTS = 8192
        private const val HELD = 16
        private const val STAMPS_PER_LOAD = 24
        private const val SETTLE_MILLIS = 200L
        private const val MEDIAN = 0.5
        private const val P90 = 0.9
        private const val PERCENT = 100
        private const val CALL = "call"
        private const val SUBMITTED = "pool task submitted"
        private const val COMPILED = "compilation ended"
        private const val INSTALLED = "installed (reported, before published)"
        private const val ANSWERED = "answered (recorded, before complete)"

        private fun percentile(values: List<Long>, p: Double): Long {
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * p).toInt()]
        }

        private fun micros(nanos: Long): String = "%.2f".format(nanos / 1000.0)

        private fun group(name: String): String = when {
            name.startsWith("cosec-snapshot-compile") -> "pool"
            name.startsWith("cosec-snapshot-housekeeping") -> "housekeeping"
            name.startsWith("cosec-snapshot-scheduler") -> "scheduler"
            name == REQUESTER -> "requester"
            else -> "other"
        }

        /** The cost of one stamp on this host, measured as the timeline records one, in nanoseconds. */
        fun stampCost(): Double {
            val timeline = ColdLoadTimeline(runner = false, loads = 1, warmup = 0)
            timeline.recording = true
            timeline.current = 0
            repeat(STAMP_WARMUP) { timeline.stamp("warm") }
            timeline.stamps.clear()
            val begin = System.nanoTime()
            repeat(STAMP_SAMPLES) { timeline.stamp("cost") }
            return (System.nanoTime() - begin).toDouble() / STAMP_SAMPLES
        }

        private const val STAMP_WARMUP = 200_000
        private const val STAMP_SAMPLES = 200_000

        /**
         * `Slot`, the runner, `Slot`, the runner, each on a thread named [REQUESTER], and the second pass of each: the
         * first pass of the JVM warms the code both share, whichever runs first. Then the runner's extra over the two
         * windows, and a stamp's cost.
         */
        fun both(loads: Int, warmup: Int): String {
            val out = StringBuilder()
            val windows = LongArray(2)
            for ((pass, runner) in listOf(1 to false, 1 to true, 2 to false, 2 to true)) {
                val thread = Thread({
                    val result = ColdLoadTimeline(runner, loads, warmup).run()
                    if (pass == 2) {
                        out.append(result.report)
                        windows[if (runner) 1 else 0] = result.window1 + result.window2
                    }
                }, REQUESTER)
                thread.start()
                thread.join()
            }
            out.appendLine("the two windows summed, median µs: Slot ${micros(windows[0])}, runner ${micros(windows[1])}")
            out.appendLine("runner's extra on the critical path (runner - Slot): ${micros(windows[1] - windows[0])} µs")
            out.appendLine("one stamp costs %.0f ns on this host".format(stampCost()))
            return out.toString()
        }
    }
}
