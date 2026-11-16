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
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.TimeUnit

/**
 * The snapshot source's cold path (RFC 0013 B2b, gate 4): a request for a tenant document that is not installed, read
 * from an in-memory store, compiled on the source's own compile pool, installed and answered. The source holds
 * [HELD] tenant documents and the store [TENANTS], requested round-robin, so every request is a miss: the document was
 * evicted long before it comes round again. The platform document is installed once, so each request's cost is the
 * tenant's: the thread hops (the compile pool and back), the store's read, the compilation of an empty document, the
 * install and the eviction it causes.
 *
 * The "before" of B2b's rewrite: the row is measured on today's slot, and again once the transitions run as events.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class SnapshotColdLoadBenchmark {
    private lateinit var source: SnapshotSource<*, *>
    private lateinit var keys: List<SnapshotKey>
    private var next = 0

    @Setup
    fun setup() {
        val store = InMemorySnapshotDocumentStore()
        val text = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
        keys = List(TENANTS) { SnapshotKey.Tenant("tenant-$it") }
        (keys + SnapshotKey.Platform).forEach { store.put(it, SnapshotDocuments.read(text.encodeToByteArray())) }
        source = DocumentSnapshots.source(store, PolicyDocumentCompiler(), SnapshotOptions(maxTenants = HELD))
        source.load().join()
        // Every key once, so each is seen: a request reads it from the store of record, as a cold request does.
        keys.forEach { source.loadAll(listOf(it)).join() }
    }

    @TearDown
    fun tearDown() {
        source.close()
    }

    @Benchmark
    fun coldLoad(): SnapshotSet<*, *> {
        val key = keys[next]
        next = (next + 1) % TENANTS
        return source.loadAll(listOf(key)).join()
    }

    private companion object {
        const val TENANTS = 4096
        const val HELD = 16
    }
}
