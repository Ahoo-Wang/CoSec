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
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Instant

/**
 * `DocumentSnapshotViews.peek`, the view the pipeline composes once per request from the installed documents, with
 * every document loaded (#1216). The platform document is published; the tenant `acme` has `tenant`: `absent`, no
 * document in the store, or `empty`, a published document with nothing in it (the same view, semantically).
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class DocumentSnapshotViewsBenchmark {
    @Param("absent", "empty")
    lateinit var tenant: String

    private lateinit var source: SnapshotSource<*, *>
    private lateinit var views: DocumentSnapshotViews
    private lateinit var key: ViewKey

    @Setup
    fun setup() {
        val store = InMemorySnapshotDocumentStore()
        fun publish(key: SnapshotKey) {
            val text = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
            store.put(key, SnapshotDocument(2, 1, Instant.parse("2026-10-26T00:00:00Z"), text.encodeToByteArray()))
        }
        publish(SnapshotKey.Platform)
        if (tenant == "empty") {
            publish(SnapshotKey.Tenant("acme"))
        }
        val compiled = DocumentSnapshots.source(store, PolicyDocumentCompiler())
        source = compiled
        views = DocumentSnapshotViews(compiled)
        val memberships = Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId.DEFAULT to emptySet()), emptyList())
        val scope = (
            ScopeResolver.resolve(ScopeSubject(TenantId("acme"), true, memberships), "", HostScope(null))
                as ScopeResolution.Resolved
            ).scope
        key = ViewKey.of(scope, blocked = false)
        views.load(key).join()
        checkNotNull(views.peek(key)) { "The view is not installed" }
    }

    @TearDown
    fun tearDown() {
        source.close()
    }

    @Benchmark
    fun peek(): SnapshotView? = views.peek(key)
}
