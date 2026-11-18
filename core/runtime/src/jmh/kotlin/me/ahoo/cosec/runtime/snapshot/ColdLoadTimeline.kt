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
@file:Suppress("all")

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
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measurement only, not for merge (RFC 0013 B2b, the cold-load row): a timeline of uncontended cold loads of tenant
 * documents, as `SnapshotColdLoadBenchmark.coldLoad` makes them, stamped from outside the source. The store, the
 * compiler, the compile pool, housekeeping, the scheduler and the metrics are wrapped. Each stamp carries the load it
 * belongs to: a pool or housekeeping task carries the load that submitted it. The loads run back to back, as in the
 * benchmark.
 */
class ColdLoadTimeline(private val runner: Boolean, private val loads: Int = 10_000, private val warmup: Int = 20_000) {
    private class Stamp(val load: Int, val name: String, val nanos: Long, val thread: String)

    private val stamps = ArrayList<Stamp>(loads * 24)

    @Volatile
    private var current = -1

    @Volatile
    private var recording = false
    private val carried = ThreadLocal<Int?>()

    private fun group(name: String): String = when {
        name.startsWith("cosec-snapshot-compile") -> "pool"
        name.startsWith("cosec-snapshot-housekeeping") -> "housekeeping"
        name.startsWith("cosec-snapshot-scheduler") -> "scheduler"
        name == REQUESTER -> "requester"
        else -> "other"
    }

    private fun stamp(name: String, load: Int = carried.get() ?: current) {
        if (!recording || load < 0) return
        val s = Stamp(load, name, System.nanoTime(), group(Thread.currentThread().name))
        synchronized(stamps) { stamps += s }
    }

    private lateinit var source: SnapshotSource<*, *>

    /** Real executors made with the source's own thread markers, once the source exists. */
    private inner class Lazy(private val make: () -> ExecutorService, private val label: String) :
        AbstractExecutorService() {
        @Volatile
        var real: ExecutorService? = null
        private val early = lazy { make() }

        private fun real(): ExecutorService = real ?: early.value

        override fun execute(command: Runnable) {
            val load = carried.get() ?: current
            stamp("$label submitted", load)
            real().execute {
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

        override fun shutdown() = real().shutdown()
        override fun shutdownNow(): MutableList<Runnable> = real().shutdownNow()
        override fun isShutdown(): Boolean = real().isShutdown
        override fun isTerminated(): Boolean = real().isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = real().awaitTermination(timeout, unit)
    }

    fun run(): String {
        val inMemory = InMemorySnapshotDocumentStore()
        val document = SnapshotDocuments.read(
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}""".encodeToByteArray()
        )
        val keys = List(TENANTS) { SnapshotKey.Tenant("tenant-$it") }
        (keys + SnapshotKey.Platform).forEach { inMemory.put(it, document) }
        val store = object : SnapshotDocumentStore by inMemory {
            override fun read(key: SnapshotKey) = reading(key) { inMemory.read(key) }

            override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?) =
                reading(key) { inMemory.readIfChanged(key, known) }

            private fun <R> reading(key: SnapshotKey, read: () -> java.util.concurrent.CompletableFuture<R>):
                java.util.concurrent.CompletableFuture<R> {
                if (key is SnapshotKey.Tenant) stamp("read started")
                return read().also { f -> if (key is SnapshotKey.Tenant) f.whenComplete { _, _ -> stamp("read answered") } }
            }
        }
        val policies = PolicyDocumentCompiler()
        val compiler = SnapshotCompiler { key, doc ->
            if (key is SnapshotKey.Tenant) stamp("compilation started")
            policies.compile(key, doc).also { if (key is SnapshotKey.Tenant) stamp("compilation ended") }
        }
        val options = SnapshotOptions(maxTenants = HELD)
        lateinit var threads: SnapshotThreads
        val pool = Lazy({ SnapshotExecutors.newCompileExecutor(options) }, "pool task")
        val housekeeping = Lazy({ SnapshotExecutors.newHousekeeping() }, "housekeeping task")
        val realScheduler = SnapshotExecutors.newScheduler(ThreadLocal())
        val scheduler = object : ScheduledExecutorService by realScheduler {
            private fun <V> counted(future: ScheduledFuture<V>): ScheduledFuture<V> {
                stamp("timer scheduled")
                return object : ScheduledFuture<V> by future {
                    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                        stamp("timer cancelled")
                        return future.cancel(mayInterruptIfRunning)
                    }
                }
            }

            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                counted(realScheduler.schedule(command, delay, unit))

            override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
                counted(realScheduler.schedule(callable, delay, unit))
        }
        val metrics = object : SnapshotMetrics {
            override fun onInstall(entry: SnapshotEntry<*>) {
                if (entry.key is SnapshotKey.Tenant) stamp("installed (reported, before published)")
            }

            override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
                if (kind == SnapshotKind.TENANT) stamp("answered ($outcome, before complete)")
            }
        }
        val slots = if (!runner) {
            SlotFactory.SLOT
        } else {
            object : SlotFactory {
                override fun <T : Any> create(key: SnapshotKey, compiler: SnapshotCompiler<T>, context: SlotContext): KeySlot<T> =
                    KeyRunner(
                        key, compiler, context, KeyRunner.K,
                        KeyDrain.Hooks(beforeNext = { if (key is SnapshotKey.Tenant) stamp("step ${it::class.simpleName}") }),
                    )
            }
        }
        source = SnapshotSource(
            store, SnapshotKey.Platform, compiler, DocumentSnapshots.families(compiler, options), options,
            Clock.systemUTC(), metrics, pool, scheduler, System::nanoTime, housekeeping, slots,
        )
        threads = source.threads
        pool.real = SnapshotExecutors.newCompileExecutor(options, threads.onOwnThread, threads.drainsUnbounded)
        housekeeping.real = SnapshotExecutors.newHousekeeping(threads.onOwnThread, threads.drainsUnbounded)
        val loadTimes = LongArray(loads)
        try {
            source.load().join()
            keys.forEach { source.loadAll(listOf(it)).join() }
            var next = 0
            repeat(warmup) { source.loadAll(listOf(keys[next++ % TENANTS])).join() }
            recording = true
            for (i in 0 until loads) {
                current = i
                val begin = System.nanoTime()
                stamp("call")
                val future = source.loadAll(listOf(keys[next++ % TENANTS]))
                stamp("request returned")
                future.whenComplete { _, _ -> stamp("future completed") }
                future.join()
                stamp("running after join")
                loadTimes[i] = System.nanoTime() - begin
            }
            Thread.sleep(200)
            recording = false
        } finally {
            source.close()
            listOf(pool.real, housekeeping.real).forEach { it?.shutdownNow() }
            realScheduler.shutdownNow()
        }
        return report(loadTimes)
    }

    private fun report(loadTimes: LongArray): String {
        val byLoad = stamps.groupBy { it.load }
        // The first stamp of each name in each load, as an offset from its call.
        val offsets = HashMap<String, MutableList<Long>>()
        val threads = HashMap<String, MutableMap<String, Int>>()
        for ((_, list) in byLoad) {
            val call = list.firstOrNull { it.name == "call" } ?: continue
            val seen = HashSet<String>()
            for (s in list.sortedBy { it.nanos }) {
                if (!seen.add(s.name)) continue
                offsets.getOrPut(s.name) { ArrayList() } += s.nanos - call.nanos
                threads.getOrPut(s.name) { HashMap() }.merge(s.thread, 1, Int::plus)
            }
        }
        fun pct(v: List<Long>, p: Double) = v.sorted()[((v.size - 1) * p).toInt()] / 1000.0
        val order = offsets.keys.sortedBy { pct(offsets.getValue(it), 0.5) }
        return buildString {
            val sorted = loadTimes.sorted()
            appendLine("== ${if (runner) "runner K=${KeyRunner.K}" else "Slot"}: $loads cold loads, call to running after join: " +
                "median %.2f µs, p90 %.2f µs".format(sorted[sorted.size / 2] / 1000.0, sorted[(sorted.size * 9) / 10] / 1000.0))
            appendLine("| stamp | loads | median µs | p90 µs | thread |")
            appendLine("|---|---|---|---|---|")
            for (name in order) {
                val v = offsets.getValue(name)
                val t = threads.getValue(name).entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} ${100 * it.value / v.size}%" }
                appendLine("| $name | ${v.size} | " + "%.2f | %.2f".format(pct(v, 0.5), pct(v, 0.9)) + " | $t |")
            }
        }
    }

    companion object {
        const val REQUESTER = "cold-requester"
        const val TENANTS = 8192
        const val HELD = 16

        /**
         * Runs Slot, the runner, Slot, the runner, each on a thread named [REQUESTER], and reports the second pass of
         * each: the first pass of the JVM warms the code both share, whichever runs first.
         */
        fun both(loads: Int = 10_000, warmup: Int = 20_000): String {
            val out = StringBuilder()
            for ((pass, runner) in listOf(1 to false, 1 to true, 2 to false, 2 to true)) {
                val t = Thread({
                    val report = ColdLoadTimeline(runner, loads, warmup).run()
                    if (pass == 2) out.append(report)
                }, REQUESTER)
                t.start()
                t.join()
            }
            return out.toString()
        }
    }
}
