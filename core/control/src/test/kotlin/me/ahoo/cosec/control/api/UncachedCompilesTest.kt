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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * DR1-F-4: what the control plane's reads compile is bounded. A retained revision is cached by its digest, so explaining
 * at one again compiles nothing; and the decisions that compile a document the cache will not keep (a candidate, one too
 * heavy to admit) run at most [DocumentCache.uncachedCompiles] at once, a count derived from the cache's heap bound.
 */
class UncachedCompilesTest {
    @Test
    fun `explaining again at a retained revision compiles nothing again`() = runBlocking<Unit> {
        val fixture = ControlPlaneTest()
        val change = fixture.plane.propose(
            fixture.alice, ControlPlaneTest.TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke",
        )
        fixture.plane.startShadow(fixture.alice, change.id)
        fixture.changes.shadowed(change.id)
        fixture.plane.publish(fixture.alice, change.id)
        val then = me.ahoo.cosec.engine.ViewVersion(1, null, 1)
        fixture.plane.explain(fixture.alice, fixture.bob("read"), then).decision.decision.assert().isEqualTo("Permit")
        val first = fixture.simulator.compilations.get()
        repeat(5) { fixture.plane.explain(fixture.alice, fixture.bob("read"), then) }
        fixture.simulator.compilations.get().assert().isEqualTo(first)
    }

    @Test
    fun `decisions that compile for the call run at most as many at once as the cache's budget allows`() = runBlocking<Unit> {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val slow = object : Engine by CoSecEngine {
            override fun evaluate(
                request: me.ahoo.cosec.engine.EngineRequest,
                snapshot: me.ahoo.cosec.engine.SnapshotView,
                trace: me.ahoo.cosec.engine.TraceLevel,
            ): me.ahoo.cosec.engine.Evaluation {
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                try {
                    Thread.sleep(SLOW_MILLIS)
                    return CoSecEngine.evaluate(request, snapshot, trace)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        val simulator = Simulator(engine = slow)
        val scope = (simulator.scope(REQUEST) as ScopeResolution.Resolved).scope
        // Not taken from the cache: a candidate's documents, compiled for the call alone.
        val candidate = HeldDocuments(Held.of(document(DocumentKind.PLATFORM, "")), null, Held.of(document(DocumentKind.TENANT, "acme")))
        simulator.compilesForTheCall(candidate).assert().isTrue()
        (1..4).map { async(Dispatchers.Default) { simulator.decideBounded(REQUEST, scope, candidate) } }.awaitAll()
        most.get().assert().isEqualTo(simulator.cache.uncachedCompiles)
        // Taken from the cache: no permit needed.
        val platform = document(DocumentKind.PLATFORM, "")
        val held = simulator.cache.put(SnapshotKey.Platform, SnapshotDigest(ByteArray(32) { 7 }), 64, platform)
        simulator.compilesForTheCall(HeldDocuments(Held(platform, held), null, null)).assert().isFalse()
    }

    @Test
    fun `the permits are what the cache's heap bound holds of the largest document, at least one`() {
        DocumentCache().uncachedCompiles.assert().isEqualTo(1)
        // 12 times the 8 MB document cap is 96 MB a compile; a gigabyte holds ten.
        DocumentCache(maxHeap = 1L shl 30).uncachedCompiles.assert().isEqualTo(10)
    }

    private companion object {
        const val SLOW_MILLIS = 50L

        val REQUEST = SimulatedRequest(
            tenant = TenantId("acme"),
            principal = "alice",
            action = Action("order", "read"),
            resource = Resource("order", "1", emptyMap()),
            time = 0,
        )

        fun document(kind: DocumentKind, owner: String): PolicyDocument = PolicyDocumentCodec.decode(
            kind,
            owner,
            """{"schema":2,"revision":1,"publishedAt":"2026-10-27T00:00:00Z"}""".encodeToByteArray(),
        )
    }
}
