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
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Step 3's question about `SnapshotColdLoadBenchmark.coldLoad` (measurement branch only): where a cold load's time goes,
 * split at its compilation, under [Slot] (`slot`) and the runner (`k4`). One thread, a fresh tenant key per request,
 * each held key evicted beyond 16 as in the gate row. Per measured request: the time before its compilation starts
 * (the request, the store's read, the hop to the compile pool), the compilation, and the time from its end to the
 * request's answer (install, eviction hand-off, answer, the hop back). Means and percentiles at the end of the trial.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class ColdLoadSplitBenchmark {
    @Param("slot", "k4")
    lateinit var slots: String

    private lateinit var source: SnapshotSource<*, *>
    private val keys = List(TENANTS) { SnapshotKey.Tenant("tenant-$it") }
    private var next = 0
    private var recording = false

    @Volatile
    private var readAt = 0L

    @Volatile
    private var compileStart = 0L

    @Volatile
    private var compileEnd = 0L
    private val toRead = LongArray(MAX)
    private val readToCompile = LongArray(MAX)
    private val calling = LongArray(MAX)
    private val hopping = LongArray(MAX)
    private val before = LongArray(MAX)
    private val compiling = LongArray(MAX)
    private val after = LongArray(MAX)
    private var n = 0

    @Setup
    fun setup() {
        val memory = InMemorySnapshotDocumentStore()
        // The time a cold read starts: under both slots, the compile is handed to the pool right after it answers.
        val store = object : SnapshotDocumentStore by memory {
            override fun read(key: SnapshotKey) = memory.read(key).also { if (key is SnapshotKey.Tenant) readAt = System.nanoTime() }
        }
        val document = SnapshotDocuments.read(TEXT.encodeToByteArray())
        (keys + SnapshotKey.Platform).forEach { memory.put(it, document) }
        val delegate = PolicyDocumentCompiler()
        val compiler = object : SnapshotCompiler<CompiledDocument> by delegate {
            override fun compile(key: SnapshotKey, document: SnapshotDocument): CompiledDocument {
                val started = System.nanoTime()
                try {
                    return delegate.compile(key, document)
                } finally {
                    if (key is SnapshotKey.Tenant) {
                        compileStart = started
                        compileEnd = System.nanoTime()
                    }
                }
            }
        }
        val options = SnapshotOptions(maxTenants = HELD)
        val factory: SlotFactory = if (slots == "slot") {
            SlotFactory.SLOT
        } else {
            object : SlotFactory {
                override fun <T : Any> create(key: SnapshotKey, compiler: SnapshotCompiler<T>, context: SlotContext): KeySlot<T> =
                    KeyRunner(key, compiler, context, 4)
            }
        }
        source = SnapshotSource(
            store, SnapshotKey.Platform, compiler, DocumentSnapshots.families(compiler, options), options,
            Clock.systemUTC(), SnapshotMetrics.NOOP, null, null, System::nanoTime, null, factory,
        )
        source.load().join()
        keys.forEach { source.loadAll(listOf(it)).join() }
    }

    @Setup(Level.Iteration)
    fun iteration(params: IterationParams) {
        recording = params.type == IterationType.MEASUREMENT
    }

    @Benchmark
    fun coldLoad(): Any {
        val key = keys[next]
        next = (next + 1) % TENANTS
        val began = System.nanoTime()
        val pending = source.loadAll(listOf(key))
        val returned = System.nanoTime()
        val set = pending.join()
        val ended = System.nanoTime()
        if (recording && n < MAX) {
            toRead[n] = readAt - began
            readToCompile[n] = compileStart - readAt
            calling[n] = returned - began
            hopping[n] = compileStart - returned
            before[n] = compileStart - began
            compiling[n] = compileEnd - compileStart
            after[n] = ended - compileEnd
            n++
        }
        return set
    }

    @TearDown
    fun tearDown() {
        source.close()
        fun report(name: String, values: LongArray): String {
            val sorted = values.copyOf(n).apply { sort() }
            fun at(q: Double) = "%.1f".format(sorted[minOf(n - 1, (q * n).toInt())] / 1_000.0)
            return "$name mean %.2f µs, p50 ${at(0.5)}, p99 ${at(0.99)}".format(sorted.average() / 1_000.0)
        }
        println("\n[$slots] n=$n; ${report("before its compilation", before)}; ${report("compiling", compiling)}; " +
            report("after it, to the answer", after))
        println("[$slots]   from the request to its read: ${report("to read", toRead)}; " +
            "from the read to the compile's start: ${report("read to compile", readToCompile)}")
        println("[$slots]   of which the request's own call (its thread's drain): ${report("call", calling)}; " +
            "from its return to the compile's start: ${report("hop", hopping)}")
    }

    private companion object {
        const val TENANTS = 65_536
        const val HELD = 16
        const val MAX = 2_000_000
        const val TEXT = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
    }
}
