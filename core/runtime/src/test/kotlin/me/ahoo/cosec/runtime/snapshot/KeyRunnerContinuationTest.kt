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

import kotlinx.coroutines.asCoroutineDispatcher
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.ThreadingFixtures.EVENT_LOOP
import me.ahoo.cosec.runtime.ThreadingFixtures.singleThread
import me.ahoo.cosec.runtime.ThreadingFixtures.startOn
import me.ahoo.cosec.runtime.call.ResumeDispatcher
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * RFC 0013 B2b §3: a load a [KeyRunner] answers completes on its drainer, here the compile pool, and the request's
 * continuation does not run there. Through the runtime's own consumer, [me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource]
 * under the pipeline, started on an event loop as the WebFlux bridge starts it: it goes on on the bridge's resume
 * dispatcher. Step 3 repeats it in the adapters, once they can select the runner.
 */
class KeyRunnerContinuationTest {
    private val eventLoop = singleThread(EVENT_LOOP)
    private val resumeThread = singleThread(RESUME)
    private val store = InMemorySnapshotDocumentStore().apply {
        put(SnapshotKey.Platform, SnapshotDocuments.read(PLATFORM.encodeToByteArray()))
    }

    @AfterEach
    fun close() {
        listOf(eventLoop, resumeThread).forEach { it.shutdownNow() }
    }

    /**
     * Compiles once the event loop is idle: the request's own drain there has returned, so the pool offers its
     * `Compiled` with no other drainer holding the key, and drains it itself.
     */
    private val compiler = object : SnapshotCompiler<CompiledDocument> {
        private val delegate = PolicyDocumentCompiler()

        override fun compile(key: SnapshotKey, document: SnapshotDocument): CompiledDocument {
            eventLoop.submit {}.get(10, TimeUnit.SECONDS)
            return delegate.compile(key, document)
        }
    }

    /** The views, recording the thread each load completed on: the drainer that answered it. */
    private class Recording(private val views: SnapshotViews) : SnapshotViews by views {
        val completedOn = CopyOnWriteArrayList<String>()

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> =
            views.load(key).whenComplete { _, _ -> completedOn += Thread.currentThread().name }
    }

    @Test
    fun `a request whose load the runner answers on the compile pool goes on on the resume dispatcher`() {
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS)
        SnapshotSource(
            store, SnapshotKey.Platform, compiler, DocumentSnapshots.families(compiler, options), options,
            Clock.systemUTC(), SnapshotMetrics.NOOP, null, null, System::nanoTime, null, keyRunners(),
        ).use { source ->
            val views = Recording(DocumentSnapshotViews(source))
            val engine = StubEngine(PERMIT)
            val pipeline = pipeline(engine = engine, views = views)
            val resume = ResumeDispatcher(resumeThread.asCoroutineDispatcher())
            val (outcome, finishedOn) = startOn(eventLoop, resume) { pipeline.evaluate(facts()) }
            outcome.proceed.assert().isTrue()
            // The load was answered by a drain on the compile pool, so the request really waited for the runner.
            views.completedOn.assert().isNotEmpty()
            views.completedOn.all { it.startsWith("cosec-snapshot-compile") }.assert().isTrue()
            engine.threads.single().name.substringBefore(" @").assert().isEqualTo(RESUME)
            finishedOn.name.substringBefore(" @").assert().isEqualTo(RESUME)
        }
    }

    private companion object {
        const val RESUME = "test-resume"
        const val PLATFORM = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
    }
}
