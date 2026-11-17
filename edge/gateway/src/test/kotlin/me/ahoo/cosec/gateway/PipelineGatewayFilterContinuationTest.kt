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

package me.ahoo.cosec.gateway

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * RFC 0013 B2b step 3: with the [me.ahoo.cosec.runtime.snapshot.KeyRunner] as the snapshot source's default, a load the
 * compile pool answers completes on its drainer there, and the filter's chain goes on on the bridge's resume dispatcher, never on
 * the drainer. The request starts on an event loop; the compiler waits until that loop is idle, so the request's own
 * drain has returned and the pool takes its own `Compiled`.
 */
class PipelineGatewayFilterContinuationTest {
    private val eventLoop = Executors.newSingleThreadExecutor { Thread(it, "test-event-loop") }

    @AfterEach
    fun close() {
        eventLoop.shutdownNow()
    }

    private val compiler = object : SnapshotCompiler<CompiledDocument> {
        private val delegate = PolicyDocumentCompiler()

        override fun compile(key: SnapshotKey, document: SnapshotDocument): CompiledDocument {
            eventLoop.submit {}.get(10, TimeUnit.SECONDS)
            return delegate.compile(key, document)
        }
    }

    /** Records the thread each view load completed on: the drainer that answered it. */
    private class Recording(private val views: SnapshotViews) : SnapshotViews by views {
        val completedOn = CopyOnWriteArrayList<String>()

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> =
            views.load(key).whenComplete { _, _ -> completedOn += Thread.currentThread().name }
    }

    private val permit = Decision.Permit(
        StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
    )

    private fun pipeline(views: SnapshotViews) =
        Pipeline.builder(Authenticator { AuthnResult.Anonymous }, SuspendingSnapshotSource(views))
            .engine(object : Engine {
                override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel) =
                    Evaluation(permit, null)
            })
            .build()

    private fun store() = InMemorySnapshotDocumentStore().apply {
        put(SnapshotKey.Platform, SnapshotDocuments.read(PLATFORM.encodeToByteArray()))
    }

    @Test
    fun `a request whose load the runner answers on the compile pool goes on on the resume dispatcher`() {
        DocumentSnapshots.source(store(), compiler).use { source ->
            val views = Recording(DocumentSnapshotViews(source))
            var chainOn: String? = null
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
            PipelineGatewayFilter(pipeline(views)).filter(exchange) {
                chainOn = Thread.currentThread().name
                Mono.empty()
            }.subscribeOn(Schedulers.fromExecutorService(eventLoop)).block(Duration.ofSeconds(10))
            views.completedOn.assert().isNotEmpty()
            views.completedOn.all { it.startsWith("cosec-snapshot-compile") }.assert().isTrue()
            chainOn!!.assert().startsWith("DefaultDispatcher-worker")
        }
    }

    private companion object {
        const val PLATFORM = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
    }
}
