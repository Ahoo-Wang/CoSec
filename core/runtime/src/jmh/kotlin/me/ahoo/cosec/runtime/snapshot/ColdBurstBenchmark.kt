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
import org.openjdk.jmh.annotations.AuxCounters
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A new replica's first moment at the target scale (round 2, R2D-4): a fresh source with the platform document
 * installed, and a burst of [burst] distinct tenants it has never seen, requested at once from one thread, as the event
 * loops of a replica the autoscaler just added would. The options are the defaults: two compile threads, a queue of 64,
 * a cold load timeout of 2 s. Each tenant document has [POLICIES] policies of [STATEMENTS] statements and [BINDINGS]
 * bindings; setup prints what one costs to compile.
 *
 * One operation is the whole burst, from the first request to the last answer, on a source made for it. [Counts] says
 * how many tenants were answered with their document (`served`) and how many with a failure (`refused`).
 * Before R2D-4 a refused compilation failed its cold load at once: beyond the pool and its queue, a burst was refused.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ColdBurstBenchmark {
    @Param("100", "500", "2000")
    var burst: Int = 0

    private val store = InMemorySnapshotDocumentStore()
    private lateinit var keys: List<SnapshotKey>
    private lateinit var source: SnapshotSource<*, *>

    /**
     * Totals per iteration: the bursts, the tenants answered with their document and those answered with a failure.
     * Divided by `bursts`, a burst's; printed at the end of each iteration.
     */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    open class Counts {
        @JvmField
        var bursts: Long = 0

        @JvmField
        var served: Long = 0

        @JvmField
        var refused: Long = 0

        @Setup(Level.Iteration)
        fun reset() {
            bursts = 0
            served = 0
            refused = 0
        }

        @TearDown(Level.Iteration)
        fun report() {
            println("ColdBurstBenchmark: $bursts bursts, served $served, refused $refused")
        }
    }

    @Setup(Level.Trial)
    fun documents() {
        keys = List(burst) { SnapshotKey.Tenant("tenant-$it") }
        store.put(SnapshotKey.Platform, SnapshotDocuments.read(MINIMAL.encodeToByteArray()))
        keys.forEachIndexed { i, key -> store.put(key, SnapshotDocuments.read(tenantDocument(i).encodeToByteArray())) }
        val compiler = PolicyDocumentCompiler()
        val document = SnapshotDocuments.read(tenantDocument(0).encodeToByteArray())
        repeat(WARM_COMPILES) { compiler.compile(keys[0], document) }
        val started = System.nanoTime()
        repeat(TIMED_COMPILES) { compiler.compile(keys[0], document) }
        val micros = (System.nanoTime() - started) / 1_000 / TIMED_COMPILES
        println("ColdBurstBenchmark: one tenant document compiles in $micros µs; burst $burst")
    }

    /** A replica just started: nothing but the platform document is installed. */
    @Setup(Level.Invocation)
    fun replica() {
        source = DocumentSnapshots.source(store, PolicyDocumentCompiler(), SnapshotOptions())
        source.load().join()
    }

    @TearDown(Level.Invocation)
    fun stop() {
        source.close()
    }

    @Benchmark
    fun burstOfColdTenants(counts: Counts): Int {
        val answers = keys.map { source.loadAll(listOf(it)) }
        CompletableFuture.allOf(*answers.toTypedArray()).join()
        var served = 0
        for (answer in answers) {
            if (answer.join().scoped.single().state is DocumentState.Present<*>) served++
        }
        counts.bursts++
        counts.served += served
        counts.refused += burst - served
        return served
    }

    private companion object {
        const val POLICIES = 30
        const val STATEMENTS = 4
        const val BINDINGS = 60
        const val WARM_COMPILES = 2_000
        const val TIMED_COMPILES = 2_000
        const val MINIMAL = """{"schema":2,"revision":1,"publishedAt":"2026-11-20T00:00:00Z"}"""

        fun tenantDocument(tenant: Int): String {
            val policies = (0 until POLICIES).joinToString(",") { p ->
                val statements = (0 until STATEMENTS).joinToString(",") { s ->
                    """{"effect":"allow","action":"http:GET","resource":"http:path:/t$tenant/p$p/s$s/**"}"""
                }
                """{"id":"p$p","statements":[$statements]}"""
            }
            val bindings = (0 until BINDINGS).joinToString(",") { b ->
                """{"id":"b$b","subject":{"role":"role-${b % 12}"},"app":"*","space":"*","policy":"p${b % POLICIES}"}"""
            }
            return """{"schema":2,"revision":1,"publishedAt":"2026-11-20T00:00:00Z",""" +
                """"policies":[$policies],"bindings":[$bindings]}"""
        }
    }
}
