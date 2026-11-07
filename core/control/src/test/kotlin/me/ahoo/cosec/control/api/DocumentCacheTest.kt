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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** RC1-41: the control plane's cache of stored documents is bounded, by entries and by an estimate of its heap. */
class DocumentCacheTest {
    private val document = StoreFixtures.candidate(SnapshotKey.Tenant("acme"))

    private fun key(index: Int) = SnapshotKey.Tenant("t$index")

    private fun digest(seed: Int) = SnapshotDigest(ByteArray(20) { seed.toByte() })

    @Test
    fun `the least recently used document leaves first`() {
        val cache = DocumentCache(maxEntries = 2, maxHeap = 1_000_000)
        cache.put(key(1), digest(1), 1, document)
        cache.put(key(2), digest(2), 1, document)
        // Reading the first makes the second the least recently used.
        cache.get(key(1), digest(1)).assert().isNotNull()
        cache.put(key(3), digest(3), 1, document)
        cache.size.assert().isEqualTo(2)
        cache.get(key(2), digest(2)).assert().isNull()
        cache.get(key(1), digest(1)).assert().isNotNull()
        // The same key at another digest is another document.
        cache.get(key(1), digest(9)).assert().isNull()
    }

    /**
     * Review M2 of #1240: a document weighs its stored size times the decoded factor, and times the compiled factor more
     * for each compiled form; what goes over the bound leaves, the least recently used first, but the newest stays.
     */
    @Test
    fun `the estimated heap is bounded, but the newest document stays`() {
        val decoded = 100L * DocumentCache.DECODED_FACTOR
        val compiledForm = 100L * DocumentCache.COMPILED_FACTOR
        val cache = DocumentCache(maxEntries = 10, maxHeap = 2 * decoded + compiledForm / 2)
        val first = cache.put(key(1), digest(1), 100, document)
        cache.put(key(2), digest(2), 100, document)
        cache.size.assert().isEqualTo(2)
        cache.estimatedHeap.assert().isEqualTo(2 * decoded)
        // Compiling the first makes it the most recently used and pushes the second out.
        first.compiled(digest(7)) { CompiledDocument.tenant(TenantId("t1"), emptyList(), emptyList()) }
        cache.get(key(2), digest(2)).assert().isNull()
        cache.get(key(1), digest(1)).assert().isSameAs(first)
        cache.estimatedHeap.assert().isEqualTo(decoded + compiledForm)
        // Alone over the bound, the newest document stays.
        cache.put(key(3), digest(3), 10_000, document)
        cache.size.assert().isEqualTo(1)
        cache.get(key(3), digest(3)).assert().isNotNull()
        cache.estimatedHeap.assert().isEqualTo(10_000L * DocumentCache.DECODED_FACTOR)
    }

    /**
     * Review M2 of #1240: the factors are not below what a decoded document and a compiled form hold, per stored byte,
     * for documents of many policies, of many bindings and of many conditions (the heaviest compiled form measured).
     * Each is measured as the least of three trials of several copies, so that other allocations only raise it.
     */
    @Test
    fun `the heap estimate is not below what a document holds`() {
        for ((name, shape) in SHAPES) {
            val stored = PolicyDocumentCodec.encode(shape)
            val decoded = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", stored)
            val compiler = PolicyDocumentCompiler.forPlatform(null)
            val decodedHeap = heapOf { PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", stored) }
            val compiledHeap = heapOf { compiler.compile(decoded) }
            decodedHeap.assert().describedAs("$name decoded, ${stored.size} bytes stored")
                .isLessThanOrEqualTo(stored.size.toLong() * DocumentCache.DECODED_FACTOR)
            compiledHeap.assert().describedAs("$name compiled, ${stored.size} bytes stored")
                .isLessThanOrEqualTo(stored.size.toLong() * DocumentCache.COMPILED_FACTOR)
        }
    }

    private fun heapOf(make: () -> Any): Long = (0 until TRIALS).minOf {
        val held = ArrayList<Any>(COPIES)
        val before = usedHeap()
        repeat(COPIES) { held += make() }
        val after = usedHeap()
        check(held.size == COPIES)
        (after - before) / COPIES
    }

    private fun usedHeap(): Long {
        repeat(GCS) {
            System.gc()
            Thread.sleep(GC_PAUSE_MILLIS)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun `the first entry put is the one every caller gets`() {
        val cache = DocumentCache()
        val first = cache.put(key(1), digest(1), 1, document)
        cache.put(key(1), digest(1), 1, StoreFixtures.candidate(SnapshotKey.Tenant("acme"))).assert().isSameAs(first)
    }

    @Test
    fun `concurrent first calls compile once, and keep two platforms' forms`() {
        val entry = DocumentCache().put(key(1), digest(1), 1, document)
        val compilations = AtomicInteger()
        val start = CountDownLatch(1)
        val compiled = CompiledDocument.tenant(TenantId("t1"), emptyList(), emptyList())
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val results = (0 until THREADS).map {
                pool.submit<Result<CompiledDocument>> {
                    start.await()
                    entry.compiled(digest(7)) {
                        compilations.incrementAndGet()
                        Thread.sleep(20)
                        compiled
                    }
                }
            }
            start.countDown()
            results.map { it.get(10, TimeUnit.SECONDS).getOrThrow() }.forEach { it.assert().isSameAs(compiled) }
        } finally {
            pool.shutdownNow()
        }
        compilations.get().assert().isEqualTo(1)
        // A second and a third platform: the oldest form leaves, so the first platform's compiles again.
        entry.compiled(digest(8)) { compilations.incrementAndGet().let { compiled } }
        entry.compiled(digest(9)) { compilations.incrementAndGet().let { compiled } }
        entry.compiled(digest(9)) { compilations.incrementAndGet().let { compiled } }
        compilations.get().assert().isEqualTo(3)
        entry.compiled(digest(7)) { compilations.incrementAndGet().let { compiled } }
        compilations.get().assert().isEqualTo(4)
    }

    private companion object {
        const val THREADS = 8
        const val TRIALS = 3
        const val COPIES = 6
        const val GCS = 3
        const val GC_PAUSE_MILLIS = 20L
        val ACME = TenantId("acme")

        fun shape(policies: List<PolicyDefinition>, bindings: List<Binding>) =
            PolicyDocument(DocumentKind.TENANT, "acme", 1, Instant.EPOCH, policies, bindings)

        fun policy(id: String, effect: Effect, actions: List<String>, resources: List<String>, condition: Condition? = null) =
            PolicyDefinition(
                PolicyId(id),
                false,
                null,
                listOf(StatementSource("s-$id", effect, actions, resources.map { ResourceSource(it) }, condition)),
            )

        /** As `ControlPlaneBenchmark`'s 7,000-policy tenant document, and two other shapes of about the same size. */
        val SHAPES: Map<String, PolicyDocument> = mapOf(
            "policies" to shape(
                (0 until 7_000).map { policy("p$it", Effect.ALLOW, listOf("svc$it:read", "svc$it:list"), listOf("svc$it/{id}/**")) },
                emptyList(),
            ),
            "bindings" to shape(
                (0 until 50).map { policy("p$it", Effect.ALLOW, listOf("svc$it:*"), listOf("svc$it/**")) },
                (0 until 7_000).map {
                    Binding(BindingId("b$it"), SubjectRef.Role(ACME, RoleId("r$it")), BindingScope(Match.Any, Match.Any), PolicyId("p${it % 50}"))
                },
            ),
            "conditions" to shape(
                (0 until 3_000).map {
                    policy(
                        "p$it",
                        if (it % 2 == 0) Effect.DENY else Effect.ALLOW,
                        listOf("http:GET", "http:POST"),
                        listOf("http:path:/api/v$it/orders/{id}", "http:path:/files/f$it/**"),
                        Condition.Eq("http.header.x-tenant-$it", Literal.Str("value-$it")),
                    )
                },
                (0 until 3_000).map {
                    Binding(BindingId("b$it"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId("p$it"))
                },
            ),
        )
    }
}
