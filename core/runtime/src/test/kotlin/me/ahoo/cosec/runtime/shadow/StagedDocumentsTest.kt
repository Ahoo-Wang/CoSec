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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `CTL-47`: what an instance reads and samples, by what it last read of the staged list. A list gates; no list kept, an
 * unreadable one, or none read yet leaves every document as before the list; and the list never makes a candidate of
 * another window, or a candidate nobody lists, give a sample.
 */
class StagedDocumentsTest {
    private val acme = SnapshotKey.Tenant("acme")
    private val beta = SnapshotKey.Tenant("beta")
    private val order = SnapshotKey.App("order")

    /** A candidate store with a list to answer, or none (`kept = false`), or a failure; it counts candidate reads. */
    private class Store : CandidateStore {
        @Volatile
        var list: StagedCandidates? = null

        @Volatile
        var failing = false

        val reads = AtomicInteger()

        override suspend fun candidate(key: SnapshotKey): StoredCandidate? {
            reads.incrementAndGet()
            return null
        }

        override suspend fun staged(): StagedCandidates? {
            check(!failing) { "the store is down" }
            return list
        }
    }

    private val store = Store()
    private val staged = StagedDocuments(store)

    private fun scope(tenant: String, app: String): Scope {
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(TenantId(tenant), true, memberships, app)
        return (ScopeResolver.resolve(subject, "", HostScope(AppId(app))) as ScopeResolution.Resolved).scope
    }

    private fun view(tenant: String, app: String) = SnapshotView(
        CompiledDocument.platform(emptyList(), emptyList()),
        CompiledDocument.app(AppId(app), emptyList(), emptyList()),
        CompiledDocument.tenant(TenantId(tenant), emptyList(), emptyList()),
    )

    private fun samples(tenant: String, app: String = "order") = staged.mayStage(scope(tenant, app), view(tenant, app))

    private fun list(vararg keys: SnapshotKey) = StagedCandidates(keys.associateWith { setOf("w-1") })

    private fun refresh() = runBlocking { staged.refresh() }

    @Test
    fun `with no list kept, every document may stage a candidate, read by its key`() {
        // Before the first read too.
        staged.mayStage(acme).assert().isTrue()
        samples("acme").assert().isTrue()
        refresh()
        staged.gates.assert().isFalse()
        listOf(SnapshotKey.Platform, acme, order).forEach { staged.mayStage(it).assert().isTrue() }
        samples("beta").assert().isTrue()
    }

    @Test
    fun `a list read gates the reads and the samples, and with nothing listed nothing is sampled`() {
        store.list = StagedCandidates.NONE
        refresh()
        staged.gates.assert().isTrue()
        listOf(SnapshotKey.Platform, acme, order).forEach { staged.mayStage(it).assert().isFalse() }
        samples("acme").assert().isFalse()

        store.list = list(acme)
        refresh()
        staged.mayStage(acme).assert().isTrue()
        staged.mayStage(beta).assert().isFalse()
        samples("acme").assert().isTrue()
        samples("beta").assert().isFalse()

        store.list = list(order)
        refresh()
        samples("beta", "order").assert().isTrue()
        samples("beta", "billing").assert().isFalse()

        // The platform's candidate is read on every request.
        store.list = list(SnapshotKey.Platform)
        refresh()
        samples("beta", "billing").assert().isTrue()
    }

    @Test
    fun `a list that cannot be read counts as no list, never as nothing staged`() {
        store.list = StagedCandidates.NONE
        refresh()
        samples("acme").assert().isFalse()
        store.failing = true
        refresh()
        staged.gates.assert().isFalse()
        staged.readAt.assert().isNull()
        staged.mayStage(acme).assert().isTrue()
        samples("acme").assert().isTrue()
    }

    @Test
    fun `an unlisted document's candidate is not read, and its requests are not sampled`() {
        val source = CachingCandidateSource(store, { _, _ -> error("nothing is compiled") })
        val listener = ShadowListener("shadow", 1.0, source, ShadowRecorder("instance-1"))
        store.list = list(acme)
        runBlocking { source.staged.refresh() }
        runBlocking { source.candidate(beta) }.assert().isNull()
        store.reads.get().assert().isZero()
        runBlocking { source.candidate(acme) }
        store.reads.get().assert().isEqualTo(1)
        listener.sample(scope("beta", "billing"), view("beta", "billing")).assert().isFalse()
        listener.sample(scope("acme", "order"), view("acme", "order")).assert().isTrue()
    }

    /**
     * §9 condition 4: a control plane that no longer keeps the list stages a candidate without listing it. An instance
     * that read the list neither reads that candidate nor samples its requests: the window gets no sample, and the old
     * control plane's rule closes a window without samples as insufficient, never as passed.
     */
    @Test
    fun `a candidate staged where the list is kept by no one any more gives no sample`() {
        val source = CachingCandidateSource(store, { _, _ -> error("never compiled: never read") })
        store.list = StagedCandidates.NONE
        runBlocking { source.staged.refresh() }
        runBlocking { source.candidate(acme) }.assert().isNull()
        store.reads.get().assert().isZero()
    }
}
