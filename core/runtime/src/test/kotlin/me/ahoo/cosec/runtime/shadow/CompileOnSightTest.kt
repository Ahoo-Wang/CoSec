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

package me.ahoo.cosec.runtime.shadow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.LoadOutcome
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotMetrics
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.InMemoryShadowReports
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `CTL-12`, compile on sight: an instance that reads the staged list compiles each window's candidate when it first sees
 * it, on the reporter's coroutine, and records one that does not compile as `CANDIDATE_ERROR` in that cycle's report,
 * whatever its traffic. §9 condition 2: that compile never goes through the snapshot source's compile pool, so windows
 * staged while documents load cold fail no load.
 */
class CompileOnSightTest {
    private val acme = SnapshotKey.Tenant("acme")

    private class Store : CandidateStore {
        val candidates = ConcurrentHashMap<SnapshotKey, StoredCandidate>()

        @Volatile
        var list: StagedCandidates? = null

        override suspend fun candidate(key: SnapshotKey): StoredCandidate? = candidates[key]

        override suspend fun staged(): StagedCandidates? = list
    }

    private val store = Store()
    private val reports = InMemoryShadowReports()
    private val recorder = ShadowRecorder("instance-1")
    private val compiled = AtomicInteger()

    @Volatile
    private var failing = false

    private val source = CachingCandidateSource(store, { key, document ->
        compiled.incrementAndGet()
        check(!failing) { "does not compile here" }
        PlatformCompiler().compileCandidate(key, document)
    })

    private val reporter = ShadowReporter(recorder, reports, { emptyMap() }, Clock.systemUTC(), candidates = store, source = source)

    private fun document(revision: Long = 2): SnapshotDocument = SnapshotDocuments.read(
        """{"schema":2,"revision":$revision,"publishedAt":"2026-11-20T00:00:00Z"}""".encodeToByteArray()
    )

    private fun stage(key: SnapshotKey, window: String, written: Boolean = true) {
        if (written) {
            store.candidates[key] = StoredCandidate(window, 1, document())
        }
        store.list = StagedCandidates(mapOf(key to setOf(window)))
    }

    private fun flush() = runBlocking { reporter.flush() }

    private fun errors(window: String): Long? = runBlocking {
        reports.reports(window).singleOrNull()?.counts?.get(ShadowOutcome.CANDIDATE_ERROR)
    }

    @Test
    fun `a window is compiled when the instance first sees it, once, with no request sampled`() {
        stage(acme, "w-1")
        flush()
        compiled.get().assert().isEqualTo(1)
        flush()
        compiled.get().assert().isEqualTo(1)
        // What a sampled request reads next is what was compiled.
        runBlocking { source.candidate(acme) }.assert().isInstanceOf(Candidate.Ready::class.java)
        compiled.get().assert().isEqualTo(1)
    }

    @Test
    fun `a candidate that does not compile is a CANDIDATE_ERROR in the same cycle's report, counted once`() {
        failing = true
        stage(acme, "w-1")
        flush()
        errors("w-1").assert().isEqualTo(1L)
        flush()
        errors("w-1").assert().isEqualTo(1L)
    }

    @Test
    fun `a window listed before its candidate is written is compiled on the next cycle`() {
        stage(acme, "w-1", written = false)
        flush()
        compiled.get().assert().isZero()
        store.candidates[acme] = StoredCandidate("w-1", 1, document())
        flush()
        compiled.get().assert().isEqualTo(1)
    }

    @Test
    fun `a new window of a document is compiled though the old one is still held`() {
        stage(acme, "w-1")
        flush()
        stage(acme, "w-2")
        flush()
        compiled.get().assert().isEqualTo(2)
        runBlocking { source.candidate(acme) }!!.window.assert().isEqualTo("w-2")
    }

    @Test
    fun `without a list nothing is compiled on sight`() {
        store.candidates[acme] = StoredCandidate("w-1", 1, document())
        flush()
        compiled.get().assert().isZero()
    }

    /** What a cold load and a hand-off came to, as the source reports them. */
    private class Loads : SnapshotMetrics {
        val failed = AtomicInteger()
        val refused = AtomicInteger()

        override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
            if (outcome == LoadOutcome.FAILED || outcome == LoadOutcome.TIMEOUT) failed.incrementAndGet()
        }

        override fun onHandOffRefused(ranInPlace: Boolean) {
            refused.incrementAndGet()
        }
    }

    @Test
    fun `a hundred windows staged while documents load cold fail no load, and none compiles on the source's pool`() {
        val documents = InMemorySnapshotDocumentStore()
        documents.put(SnapshotKey.Platform, document(1))
        val tenants = (1..TENANTS).map { "t-$it" }
        tenants.forEach { documents.put(SnapshotKey.Tenant(it), document(1)) }
        val compiler = PlatformCompiler()
        val loads = Loads()
        val options = SnapshotOptions(revalidateInterval = Duration.ofSeconds(50))
        val threads = ConcurrentHashMap.newKeySet<String>()
        val candidates = CachingCandidateSource(store, { key, document ->
            threads += Thread.currentThread().name
            compiler.compileCandidate(key, document)
        })
        store.list = StagedCandidates(tenants.take(WINDOWS).associate { SnapshotKey.Tenant(it) to setOf("w-$it") })
        tenants.take(WINDOWS).forEach { store.candidates[SnapshotKey.Tenant(it)] = StoredCandidate("w-$it", 1, document()) }
        val reporter = ShadowReporter(recorder, reports, { emptyMap() }, candidates = store, source = candidates)
        DocumentSnapshots.source(documents, compiler, options, Clock.systemUTC(), loads).also(compiler::bind).use { source ->
            source.load().join()
            val views = DocumentSnapshotViews(source, platformRequired = true)
            runBlocking(Dispatchers.Default) {
                val sighted = async { reporter.flush() }
                val viewed = tenants.map { tenant -> views.load(ViewKey.of(scope(tenant), blocked = false)) }
                viewed.forEach { it.join().unresolved.assert().isEmpty() }
                sighted.await()
            }
        }
        loads.failed.get().assert().isZero()
        loads.refused.get().assert().isZero()
        threads.filter { it.startsWith("cosec-snapshot") }.assert().isEmpty()
        tenants.take(WINDOWS).forEach { tenant ->
            runBlocking { candidates.candidate(SnapshotKey.Tenant(tenant)) }.assert().isInstanceOf(Candidate.Ready::class.java)
        }
        recorder.reports().assert().isEmpty()
    }

    private fun scope(tenant: String) = (
        ScopeResolver.resolve(
            ScopeSubject(TenantId(tenant), true, Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())),
            "",
            HostScope(null),
        ) as ScopeResolution.Resolved
        ).scope

    private companion object {
        const val TENANTS = 300
        const val WINDOWS = 100
    }
}
