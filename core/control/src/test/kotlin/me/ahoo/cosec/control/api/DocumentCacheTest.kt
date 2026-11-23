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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
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
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.GraphLayout
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** RC1-41: the control plane's cache of stored documents is bounded, by entries and by an estimate of its heap. */
class DocumentCacheTest {
    private val document = StoreFixtures.candidate(SnapshotKey.Tenant("acme"))

    private fun key(index: Int) = SnapshotKey.Tenant("t$index")

    private fun digest(seed: Int) = SnapshotDigest(ByteArray(32) { seed.toByte() })

    @Test
    fun `the least recently used document leaves first`() {
        // Six documents of one stored byte fill the bound, decoded; a document is admitted up to half of it.
        val one = DocumentCache.DECODED_FACTOR + DocumentCache.ENTRY_BYTES
        val bound = maxOf(6 * one, 2 * (12 + DocumentCache.ENTRY_BYTES + DocumentCache.FORM_BYTES))
        val cache = DocumentCache(maxHeap = bound)
        check(cache.admits(1) && 7 * one > bound)
        (1..6).forEach { cache.put(key(it), digest(it), 1, document) }
        // Reading the first makes the second the least recently used.
        cache.get(key(1), digest(1)).assert().isNotNull()
        cache.put(key(7), digest(7), 1, document)
        cache.size.assert().isEqualTo(6)
        cache.get(key(2), digest(2)).assert().isNull()
        cache.get(key(1), digest(1)).assert().isNotNull()
        // The same key at another digest is another document.
        cache.get(key(1), digest(9)).assert().isNull()
    }

    /**
     * Review M2 of #1240: a document weighs its stored size times the decoded factor, and times the compiled factor more
     * for each compiled form; what goes over the bound leaves, the least recently used first.
     */
    @Test
    fun `the estimated heap is bounded`() {
        val decoded = 100L * DocumentCache.DECODED_FACTOR + DocumentCache.ENTRY_BYTES
        val compiledForm = 100L * DocumentCache.COMPILED_FACTOR + DocumentCache.FORM_BYTES
        val cache = DocumentCache(maxHeap = 2 * (decoded + compiledForm))
        val first = cache.put(key(1), digest(1), 100, document)
        val second = cache.put(key(2), digest(2), 100, document)
        cache.put(key(3), digest(3), 100, document)
        cache.estimatedHeap.assert().isEqualTo(3 * decoded)
        // Compiling makes an entry the most recently used, and what it adds pushes the least recently used out.
        first.compiled(digest(7)) { CompiledDocument.tenant(TenantId("t1"), emptyList(), emptyList()) }
        cache.size.assert().isEqualTo(3)
        second.compiled(digest(7)) { CompiledDocument.tenant(TenantId("t2"), emptyList(), emptyList()) }
        cache.get(key(3), digest(3)).assert().isNull()
        cache.get(key(1), digest(1)).assert().isSameAs(first)
        cache.estimatedHeap.assert().isEqualTo(2 * (decoded + compiledForm))
    }

    /**
     * R2-L2 of #1240's review: a document that, decoded and compiled once, would weigh more than half the bound is not
     * admitted: it pushes no other entry out, and what it compiles goes with the call.
     */
    @Test
    fun `a document too heavy to admit pushes nothing out`() {
        // Admitted up to half of the bound: 1,000 stored bytes, twelve times, and the fixed parts.
        val cache = DocumentCache(maxHeap = 2 * (12_000 + DocumentCache.ENTRY_BYTES + DocumentCache.FORM_BYTES))
        val small = cache.put(key(1), digest(1), 100, document)
        small.compiled(digest(7)) { CompiledDocument.tenant(TenantId("t1"), emptyList(), emptyList()) }
        val held = cache.estimatedHeap
        cache.admits(1_000).assert().isTrue()
        cache.admits(1_001).assert().isFalse()
        val heavy = cache.put(key(2), digest(2), 1_001, document)
        var compilations = 0
        repeat(2) {
            heavy.compiled(digest(7)) {
                compilations++
                CompiledDocument.tenant(TenantId("t2"), emptyList(), emptyList())
            }
        }
        // Compiled once for the entry the call holds; nothing of it in the cache.
        compilations.assert().isEqualTo(1)
        cache.get(key(2), digest(2)).assert().isNull()
        cache.get(key(1), digest(1)).assert().isSameAs(small)
        cache.estimatedHeap.assert().isEqualTo(held)
        // The next call puts it again: another entry, compiled again.
        cache.put(key(2), digest(2), 1_001, document).assert().isNotSameAs(heavy)
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

    /**
     * DR1-F-5: without a count bound, what the smallest documents retain must be within their estimate, or many small
     * tenants would hold more than the share. Measured with JOL as what one more entry, then its compiled form, add to
     * the cache's graph, for the smallest document a tenant has: no entries, so nothing of it scales with its size.
     */
    @Test
    fun `the smallest documents hold no more heap than they are estimated at`() {
        // An entry holds lambdas, hidden classes whose field offsets JOL reads only so (before its first use here).
        System.setProperty("jol.magicFieldOffset", "true")
        val stored = PolicyDocumentCodec.encode(document)
        val decoded = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", stored)
        val cache = DocumentCache(maxHeap = 1L shl 30)
        fun retained() = GraphLayout.parseInstance(cache).totalSize()
        val empty = retained()

        val entry = cache.put(key(1), digest(1), stored.size, decoded)
        val held = retained() - empty
        entry.compiled(digest(7)) { PolicyDocumentCompiler.forPlatform(null).compile(decoded) }.getOrThrow()
        val form = retained() - empty - held

        val measured = "an entry of ${stored.size} stored bytes retains $held, its compiled form $form"
        held.assert().describedAs(measured)
            .isLessThanOrEqualTo(stored.size * DocumentCache.DECODED_FACTOR + DocumentCache.ENTRY_BYTES)
        form.assert().describedAs(measured)
            .isLessThanOrEqualTo(stored.size * DocumentCache.COMPILED_FACTOR + DocumentCache.FORM_BYTES)
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
